// 声明登录请求 DTO 所属的包。
package com.stockmanager.system.auth.dto;

// 引入非空校验注解。
import jakarta.validation.constraints.NotBlank;

// 登录请求对象；控制器会将 JSON 请求体反序列化为该 record。
public record LoginRequest(
        // 待查询的登录用户名。
        @NotBlank String username,
        // 待校验的原始密码。
        @NotBlank String password,
        // 是否记住登录状态；当前 JWT 时长仍由服务端统一配置决定。
        Boolean rememberMe
) {
    // 没有额外业务逻辑，参数校验和登录处理由其他层负责。
}
