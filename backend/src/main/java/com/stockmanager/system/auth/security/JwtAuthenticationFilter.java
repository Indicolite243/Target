// 声明 JWT 认证过滤器所属的包。
package com.stockmanager.system.auth.security;

// 引入 JWT 声明类型。
import io.jsonwebtoken.Claims;
// 引入 Servlet 过滤器链。
import jakarta.servlet.FilterChain;
// 引入 Servlet 过滤器异常。
import jakarta.servlet.ServletException;
// 引入 HTTP 请求和响应对象。
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
// 引入用户名密码认证令牌实现。
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
// 引入简单角色权限实现。
import org.springframework.security.core.authority.SimpleGrantedAuthority;
// 引入当前请求线程的安全上下文。
import org.springframework.security.core.context.SecurityContextHolder;
// 引入 Spring 组件注册注解。
import org.springframework.stereotype.Component;
// 引入保证每个请求只执行一次的过滤器基类。
import org.springframework.web.filter.OncePerRequestFilter;

// 引入 IO 异常和不可变列表。
import java.io.IOException;
import java.util.List;

// 解析 Bearer JWT，并在校验通过后建立 Spring Security 身份上下文。
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // JWT 签发与解析服务。
    private final JwtService jwtService;
    // Redis 令牌黑名单服务。
    private final JwtTokenBlacklist tokenBlacklist;

    // 注入 JWT 校验依赖和黑名单依赖。
    public JwtAuthenticationFilter(JwtService jwtService, JwtTokenBlacklist tokenBlacklist) {
        // 保存 JWT 服务。
        this.jwtService = jwtService;
        // 保存黑名单服务。
        this.tokenBlacklist = tokenBlacklist;
    }

    /** SSE 会触发一次 ASYNC dispatcher；该阶段也必须从同一 Authorization 头恢复用户身份。 */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    // 读取 Authorization 请求头，并为当前请求安装认证信息。
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 读取客户端传来的 Authorization 请求头。
        String header = request.getHeader("Authorization");
        // 只有 Bearer 格式的请求头才进入 JWT 解析流程。
        if (header != null && header.startsWith("Bearer ")) {
            // JWT 解析失败时不能阻断公共接口，后续权限规则会决定是否返回 401。
            try {
                // 去掉 Bearer 前缀并解析、验签、检查有效期。
                Claims claims = jwtService.parse(header.substring(7));
                // 即使 JWT 本身有效，已被注销的令牌也不能建立认证上下文。
                if (tokenBlacklist.isRevoked(claims)) {
                    // 清除当前线程可能残留的认证信息。
                    SecurityContextHolder.clearContext();
                    // 继续执行过滤器链，由后续规则处理请求。
                    filterChain.doFilter(request, response);
                    // 防止黑名单分支在方法末尾再次执行过滤器链。
                    return;
                }
                // 读取令牌中的角色代码。
                String role = claims.get("role", String.class);
                // 以 subject 作为用户 ID，以 ROLE_ 前缀建立 Spring Security 权限。
                var authentication = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                // 将认证对象放入当前请求线程的安全上下文。
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                // 非法、过期或格式错误的令牌按匿名请求继续，并清理上下文。
                SecurityContextHolder.clearContext();
            }
        }
        // 无论是否存在令牌，都继续处理后面的过滤器和控制器。
        filterChain.doFilter(request, response);
    }
}
