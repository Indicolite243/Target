package com.stockmanager.config;

import com.stockmanager.system.auth.security.JwtAuthenticationFilter;
import com.stockmanager.system.auth.security.CompatiblePasswordEncoder;
import com.stockmanager.assistant.mcp.InternalMcpTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * HTTP 安全配置，定义 JWT 无状态认证链路和公开端点白名单。
 *
 * <p>除登录、注册、健康检查和接口文档外，所有请求都必须先通过 JWT 过滤器建立
 * {@code SecurityContext}。具体资源归属仍由业务服务使用用户 ID 二次校验。</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    /** 构建无状态 Spring Security 过滤器链。 */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
            @Value("${app.assistant.mcp.internal-token}") String mcpInternalToken,
            @Value("${app.assistant.mcp.endpoint:/internal/mcp}") String configuredMcpEndpoint) throws Exception {
        String mcpEndpoint = InternalMcpTokenFilter.normalizeEndpoint(configuredMcpEndpoint);
        /*
         * 安全边界：
         * 请求 -> JwtAuthenticationFilter -> SecurityContext -> anyRequest().authenticated()
         * 注册/登录/健康检查/文档是白名单，其余 API 必须带有效 JWT。
         */
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/health", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers(mcpEndpoint, mcpEndpoint + "/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new InternalMcpTokenFilter(mcpInternalToken, mcpEndpoint), JwtAuthenticationFilter.class)
                .build();
    }

    /** 提供兼容旧 BCrypt 摘要和新 SHA-256 预哈希方案的密码编码器。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        // 统一由 Spring 注入密码编码器，业务层只处理 hash，不接触明文存储。
        return new CompatiblePasswordEncoder();
    }

    /** 暴露 Spring Security 认证管理器，供后续认证方式扩展。 */
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
