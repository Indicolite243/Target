package com.stockmanager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 跨域配置。
 *
 * <p>开发环境只允许本机 Vite 的两个常用域名访问 API，并暴露链路追踪响应头。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    /** 注册开发环境 API 的 CORS 白名单、方法和缓存时间。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
