package com.stockmanager.system.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stockmanager.common.exception.BusinessException;
import com.stockmanager.account.service.AccountProvisioningService;
import com.stockmanager.system.auth.dto.LoginRequest;
import com.stockmanager.system.auth.dto.RegisterRequest;
import com.stockmanager.system.auth.entity.User;
import com.stockmanager.system.auth.mapper.UserMapper;
import com.stockmanager.system.auth.security.JwtService;
import com.stockmanager.system.auth.security.JwtTokenBlacklist;
import com.stockmanager.system.auth.service.AuthService;
import com.stockmanager.system.auth.vo.LoginView;
import com.stockmanager.system.auth.vo.UserView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuthServiceImpl implements AuthService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtTokenBlacklist tokenBlacklist;
    private final AccountProvisioningService accountProvisioningService;

    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService,
                           JwtTokenBlacklist tokenBlacklist,
                           AccountProvisioningService accountProvisioningService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenBlacklist = tokenBlacklist;
        this.accountProvisioningService = accountProvisioningService;
    }

    @Override
    @Transactional
    public UserView register(RegisterRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new BusinessException(400100, "两次输入的密码不一致", HttpStatus.BAD_REQUEST);
        }
        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName() == null || request.displayName().isBlank()
                ? request.username() : request.displayName());
        user.setRoleCode("USER");
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        try {
            userMapper.insert(user);
            accountProvisioningService.createSimulationAccount(user.getId(), user.getUsername());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(409001, "用户名已存在", HttpStatus.CONFLICT);
        }
        return toView(user);
    }

    @Override
    public LoginView login(LoginRequest request) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, request.username()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(401001, "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(403001, "账号不可用", HttpStatus.FORBIDDEN);
        }
        return new LoginView(jwtService.issue(user.getId(), user.getUsername(), user.getRoleCode()),
                "Bearer", jwtService.expirationSeconds(), toView(user));
    }

    @Override
    public void logout(String accessToken) {
        tokenBlacklist.revoke(jwtService.parse(accessToken));
    }

    @Override
    public UserView currentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404001, "用户不存在", HttpStatus.NOT_FOUND);
        }
        return toView(user);
    }

    private UserView toView(User user) {
        List<String> permissions = "ADMIN".equals(user.getRoleCode())
                ? List.of("admin:user:read", "account:read", "order:create", "risk:read")
                : List.of("account:read", "order:create", "risk:read", "analysis:read");
        return new UserView(String.valueOf(user.getId()), user.getUsername(), user.getDisplayName(),
                List.of(user.getRoleCode()), permissions, user.getStatus());
    }
}
