// 声明登录响应 VO 所属的包；VO 只承载允许返回给客户端的数据。
package com.stockmanager.system.auth.vo;

// 登录成功响应对象，包含令牌信息和脱敏后的用户信息。
public record LoginView(
        // JWT 访问令牌，客户端后续请求放入 Bearer Authorization 请求头。
        String accessToken,
        // 令牌类型，当前接口固定返回 Bearer。
        String tokenType,
        // 令牌有效时间，单位为秒。
        long expiresIn,
        // 当前登录用户的安全视图，不包含密码摘要。
        UserView user
) {
    // record 自动生成构造方法、访问器和常用值对象方法。
}
