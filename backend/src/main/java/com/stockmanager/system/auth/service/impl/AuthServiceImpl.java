// 声明认证服务实现所属的包。
package com.stockmanager.system.auth.service.impl;

// 引入 MyBatis-Plus 条件构造器工具。
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
// 引入项目统一业务异常。
import com.stockmanager.common.exception.BusinessException;
// 引入注册时创建默认模拟账户的服务。
import com.stockmanager.account.service.AccountProvisioningService;
// 引入认证请求 DTO、用户实体、Mapper、JWT 服务和黑名单服务。
import com.stockmanager.system.auth.dto.LoginRequest;
import com.stockmanager.system.auth.dto.RegisterRequest;
import com.stockmanager.system.auth.entity.User;
import com.stockmanager.system.auth.mapper.UserMapper;
import com.stockmanager.system.auth.security.JwtService;
import com.stockmanager.system.auth.security.JwtTokenBlacklist;
import com.stockmanager.system.auth.service.AuthService;
// 引入认证响应 VO。
import com.stockmanager.system.auth.vo.LoginView;
import com.stockmanager.system.auth.vo.UserView;
// 引入数据库唯一键冲突异常。
import org.springframework.dao.DuplicateKeyException;
// 引入 HTTP 状态码。
import org.springframework.http.HttpStatus;
// 引入 Spring Security 密码编码器抽象。
import org.springframework.security.crypto.password.PasswordEncoder;
// 引入 Spring 服务组件注解。
import org.springframework.stereotype.Service;
// 引入事务注解，保证注册及默认账户创建具有原子性。
import org.springframework.transaction.annotation.Transactional;

// 引入用户时间字段使用的类型。
import java.time.LocalDateTime;
// 引入权限列表使用的 List。
import java.util.List;

// 认证服务实现：负责注册、登录、JWT 签发、退出和当前用户查询。
@Service
public class AuthServiceImpl implements AuthService {
    // 用户表数据访问对象。
    private final UserMapper userMapper;
    // 密码编码与校验组件。
    private final PasswordEncoder passwordEncoder;
    // JWT 签发与解析组件。
    private final JwtService jwtService;
    // JWT Redis 黑名单组件。
    private final JwtTokenBlacklist tokenBlacklist;
    // 默认模拟账户初始化组件。
    private final AccountProvisioningService accountProvisioningService;

    // 注入完成认证流程所需的全部依赖。
    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService,
                           JwtTokenBlacklist tokenBlacklist,
                           AccountProvisioningService accountProvisioningService) {
        // 保存用户 Mapper。
        this.userMapper = userMapper;
        // 保存密码编码器。
        this.passwordEncoder = passwordEncoder;
        // 保存 JWT 服务。
        this.jwtService = jwtService;
        // 保存令牌黑名单服务。
        this.tokenBlacklist = tokenBlacklist;
        // 保存默认账户初始化服务。
        this.accountProvisioningService = accountProvisioningService;
    }

    // 注册用户，并在同一事务中创建该用户的默认模拟账户。
    @Override
    @Transactional
    public UserView register(RegisterRequest request) {
        // 两次密码必须完全一致，否则拒绝创建用户。
        if (!request.password().equals(request.confirmPassword())) {
            // 使用 400 表示客户端提交的确认密码不符合要求。
            throw new BusinessException(400100, "两次输入的密码不一致", HttpStatus.BAD_REQUEST);
        }
        // 创建待持久化的用户实体。
        User user = new User();
        // 写入注册用户名。
        user.setUsername(request.username());
        // 只保存编码后的密码摘要，绝不保存原始密码。
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // 展示名称为空或全是空白时，回退为登录用户名。
        user.setDisplayName(request.displayName() == null || request.displayName().isBlank()
                // 使用用户名作为默认展示名称。
                ? request.username() : request.displayName());
        // 新注册用户默认角色为普通用户。
        user.setRoleCode("USER");
        // 新注册用户默认处于可登录状态。
        user.setStatus("ACTIVE");
        // 记录创建时间。
        user.setCreatedAt(LocalDateTime.now());
        // 初始化更新时间为当前时间。
        user.setUpdatedAt(LocalDateTime.now());
        // 数据库写入可能因并发注册相同用户名而触发唯一键异常。
        try {
            // 插入用户；主键可能由 MyBatis-Plus 自动生成并回填到 user.id。
            userMapper.insert(user);
            // 在同一事务中创建默认模拟账户，失败时用户插入也会回滚。
            accountProvisioningService.createSimulationAccount(user.getId(), user.getUsername());
        } catch (DuplicateKeyException ex) {
            // 将数据库唯一键异常转换为稳定的业务错误码和 HTTP 409。
            throw new BusinessException(409001, "用户名已存在", HttpStatus.CONFLICT);
        }
        // 返回不含 passwordHash 的安全用户视图。
        return toView(user);
    }

    // 查询用户并校验密码、账户状态，成功后签发 JWT。
    @Override
    public LoginView login(LoginRequest request) {
        // 按用户名查询用户记录；登录响应只返回必要信息和 JWT。
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, request.username()));
        // 用户不存在或密码不匹配时使用相同错误，避免泄露账号是否存在。
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // 返回 401 未认证状态。
            throw new BusinessException(401001, "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        // 密码正确但账户被禁用时，拒绝继续签发令牌。
        if (!"ACTIVE".equals(user.getStatus())) {
            // 返回 403，表示身份可能正确但账户当前不可用。
            throw new BusinessException(403001, "账号不可用", HttpStatus.FORBIDDEN);
        }
        // 签发令牌，并返回令牌类型、有效期和用户安全视图。
        return new LoginView(jwtService.issue(user.getId(), user.getUsername(), user.getRoleCode()),
                "Bearer", jwtService.expirationSeconds(), toView(user));
    }

    // 主动注销访问令牌。
    @Override
    public void logout(String accessToken) {
        // JWT 本身无状态，因此将其 jti 写入 Redis 黑名单直到自然过期。
        tokenBlacklist.revoke(jwtService.parse(accessToken));
    }

    // 根据认证上下文中的用户 ID 读取最新用户资料。
    @Override
    public UserView currentUser(Long userId) {
        // 使用主键查询用户实体。
        User user = userMapper.selectById(userId);
        // 用户已被删除时返回资源不存在。
        if (user == null) {
            // 转换为统一的 404 业务异常。
            throw new BusinessException(404001, "用户不存在", HttpStatus.NOT_FOUND);
        }
        // 将数据库实体转换为不包含密码摘要的安全视图。
        return toView(user);
    }

    // 将用户实体转换成接口响应，并根据角色生成当前权限集合。
    private UserView toView(User user) {
        // ADMIN 获得管理员用户读取权限和账户、订单、风控权限。
        List<String> permissions = "ADMIN".equals(user.getRoleCode())
                ? List.of("admin:user:read", "account:read", "order:create", "risk:read")
                // 普通用户获得账户、订单、风控和分析读取权限。
                : List.of("account:read", "order:create", "risk:read", "analysis:read");
        // 映射公开字段、角色、权限和状态；不映射 passwordHash。
        return new UserView(String.valueOf(user.getId()), user.getUsername(), user.getDisplayName(),
                List.of(user.getRoleCode()), permissions, user.getStatus());
    }
}
