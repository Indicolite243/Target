// 声明当前类所在的 Java 包；DTO 专门用于接收注册接口的请求参数。
package com.stockmanager.system.auth.dto;

// 引入“不能为空”校验注解。
import jakarta.validation.constraints.NotBlank;

// 注册请求 DTO；record 会自动生成构造方法和字段访问器。
public record RegisterRequest(
        // 注册用户名，不能为 null、空字符串或只包含空白。
        @NotBlank String username,
        // 原始密码，仅在本次请求中使用，入库前会被编码。
        @NotBlank String password,
        // 确认密码，用于检查两次密码输入是否一致。
        @NotBlank String confirmPassword,
        // 可选的页面展示名称；为空时服务层会使用用户名。
        String displayName
) {
    // record 主体没有额外逻辑，字段访问器由编译器自动生成。
}
