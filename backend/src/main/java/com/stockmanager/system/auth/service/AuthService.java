package com.stockmanager.system.auth.service;

import com.stockmanager.system.auth.dto.LoginRequest;
import com.stockmanager.system.auth.dto.RegisterRequest;
import com.stockmanager.system.auth.vo.LoginView;
import com.stockmanager.system.auth.vo.UserView;

public interface AuthService {
    UserView register(RegisterRequest request);
    LoginView login(LoginRequest request);
    void logout(String accessToken);
    UserView currentUser(Long userId);
}
