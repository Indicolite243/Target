// 声明认证控制器所属的包。
package com.stockmanager.system.auth.controller;

// 引入统一 API 响应包装类。
import com.stockmanager.common.response.ApiResponse;
// 引入登录和注册请求 DTO。
import com.stockmanager.system.auth.dto.LoginRequest;
import com.stockmanager.system.auth.dto.RegisterRequest;
// 引入认证业务服务。
import com.stockmanager.system.auth.service.AuthService;
// 引入登录和用户响应 VO。
import com.stockmanager.system.auth.vo.LoginView;
import com.stockmanager.system.auth.vo.UserView;
// 引入读取请求链路信息的 Servlet 请求对象。
import jakarta.servlet.http.HttpServletRequest;
// 引入 @Valid，使请求 DTO 上的校验注解生效。
import jakarta.validation.Valid;
// 引入 Spring Security 当前认证身份。
import org.springframework.security.core.Authentication;
// 引入 REST 控制器和请求映射注解。
import org.springframework.web.bind.annotation.*;

// 提供注册、登录、当前用户和退出登录 HTTP 接口；业务逻辑委托给服务层。
@RestController
// 为所有认证接口统一添加 /api/v1/auth 前缀。
@RequestMapping("/api/v1/auth")
public class AuthController {
    // 认证业务服务依赖。
    private final AuthService authService;

    // 通过构造方法注入认证服务。
    public AuthController(AuthService authService) {
        // 保存认证服务实例。
        this.authService = authService;
    }

    // 接收注册请求，创建用户并返回安全用户视图。
    @PostMapping("/register")
    public ApiResponse<UserView> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        // @RequestBody 将 JSON 转为 DTO，@Valid 先执行参数校验，再调用注册服务。
        return ApiResponse.success("注册成功", authService.register(request), traceId(servletRequest));
    }

    // 接收登录请求，校验账号密码并返回 JWT 与用户摘要。
    @PostMapping("/login")
    public ApiResponse<LoginView> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        // 将登录结果和当前请求链路 ID 统一包装后返回。
        return ApiResponse.success("登录成功", authService.login(request), traceId(servletRequest));
    }

    // 查询当前已认证用户的最新数据库信息。
    @GetMapping("/me")
    public ApiResponse<UserView> me(Authentication authentication, HttpServletRequest request) {
        // SecurityContext 中的 name 保存 subject，即用户 ID；查询后返回安全视图。
        return ApiResponse.success(authService.currentUser(Long.valueOf(authentication.getName())), traceId(request));
    }

    // 主动注销当前 Bearer JWT。
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(name = "Authorization") String authorization,
                                    HttpServletRequest request) {
        // 去掉固定的 Bearer 前缀，把纯 JWT 交给服务层写入 Redis 黑名单。
        authService.logout(authorization.substring("Bearer ".length()));
        // 退出接口没有业务数据，因此返回 null 数据和链路 ID。
        return ApiResponse.success("退出成功", null, traceId(request));
    }

    // 从请求属性中读取全局链路追踪 ID。
    private String traceId(HttpServletRequest request) {
        // 请求属性可能不是 String，因此统一转成字符串返回。
        return String.valueOf(request.getAttribute("traceId"));
    }
}
