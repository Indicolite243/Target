// 声明认证服务接口所属的包。
package com.stockmanager.system.auth.service;

// 引入认证服务使用的请求 DTO 和响应 VO。
import com.stockmanager.system.auth.dto.LoginRequest;
import com.stockmanager.system.auth.dto.RegisterRequest;
import com.stockmanager.system.auth.vo.LoginView;
import com.stockmanager.system.auth.vo.UserView;

// 定义用户注册、登录、退出和当前身份查询的业务契约。
public interface AuthService {
    // 注册用户并初始化默认模拟账户。
    UserView register(RegisterRequest request);
    // 校验账号密码并签发 JWT。
    LoginView login(LoginRequest request);
    // 主动注销访问令牌。
    void logout(String accessToken);
    // 根据认证上下文中的用户 ID 查询最新用户安全信息。
    UserView currentUser(Long userId);
}
