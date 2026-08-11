package com.stockmanager.system.auth.controller;

import com.stockmanager.common.response.ApiResponse;
import com.stockmanager.system.auth.dto.LoginRequest;
import com.stockmanager.system.auth.dto.RegisterRequest;
import com.stockmanager.system.auth.service.AuthService;
import com.stockmanager.system.auth.vo.LoginView;
import com.stockmanager.system.auth.vo.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<UserView> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success("注册成功", authService.register(request), traceId(servletRequest));
    }

    @PostMapping("/login")
    public ApiResponse<LoginView> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return ApiResponse.success("登录成功", authService.login(request), traceId(servletRequest));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(Authentication authentication, HttpServletRequest request) {
        return ApiResponse.success(authService.currentUser(Long.valueOf(authentication.getName())), traceId(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        return ApiResponse.success("退出成功", null, traceId(request));
    }

    private String traceId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("traceId"));
    }
}
