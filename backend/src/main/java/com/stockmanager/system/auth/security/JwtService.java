// 声明 JWT 服务所属的包。
package com.stockmanager.system.auth.security;

// 引入 JWT 声明对象。
import io.jsonwebtoken.Claims;
// 引入 JJWT 的构建器和解析器入口。
import io.jsonwebtoken.Jwts;
// 引入根据密钥材料创建 HMAC 密钥的工具。
import io.jsonwebtoken.security.Keys;
// 引入从应用配置读取属性的注解。
import org.springframework.beans.factory.annotation.Value;
// 引入 Spring 服务组件注解。
import org.springframework.stereotype.Service;

// 引入 HMAC 使用的密钥类型。
import javax.crypto.SecretKey;
// 引入 UTF-8 字节转换。
import java.nio.charset.StandardCharsets;
// 引入不可变的时间点类型。
import java.time.Instant;
// 引入 JJWT 使用的日期类型。
import java.util.Date;
// 引入随机 UUID，用作每个令牌唯一的 jti。
import java.util.UUID;

// 负责签发和解析 JWT。
@Service
public class JwtService {
    // 用于签名和验签的 HMAC 密钥。
    private final SecretKey key;
    // 令牌有效期，单位为秒。
    private final long expirationSeconds;

    // 从配置读取密钥和有效期，并完成启动时校验。
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-seconds:2592000}") long expirationSeconds) {
        // HMAC 密钥至少需要 32 个 UTF-8 字节，避免使用过短弱密钥。
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            // 配置不安全时立即阻止应用启动。
            throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        }
        // 有效期必须为正数，否则令牌会立即失效或无法正确生成。
        if (expirationSeconds <= 0) {
            // 抛出异常提示配置错误。
            throw new IllegalArgumentException("JWT expiration must be positive");
        }
        // 将配置字符串转换为 JJWT 使用的 HMAC 密钥对象。
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        // 保存经过校验的令牌有效期。
        this.expirationSeconds = expirationSeconds;
    }

    // 为指定用户签发访问令牌，每次签发都会生成唯一 jti。
    public String issue(Long userId, String username, String role) {
        // 记录签发时刻，保证 issuedAt 和 expiration 使用同一时间基准。
        Instant now = Instant.now();
        // 构造声明、签名并压缩为最终 JWT 字符串。
        return Jwts.builder()
                // subject 保存用户 ID，认证过滤器会用它建立当前身份。
                .subject(String.valueOf(userId))
                // 保存用户名，供需要读取令牌声明的逻辑使用。
                .claim("username", username)
                // 保存角色代码，过滤器会将其转换为 ROLE_* 权限。
                .claim("role", role)
                // 生成唯一令牌 ID，登出时可单独加入黑名单。
                .id(UUID.randomUUID().toString())
                // 写入签发时间。
                .issuedAt(Date.from(now))
                // 写入当前时间加有效秒数后的过期时间。
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                // 使用服务端密钥签名。
                .signWith(key)
                // 完成序列化，返回紧凑 JWT。
                .compact();
    }

    // 验证 JWT 签名和有效期，并返回令牌声明；非法令牌由 JJWT 抛出异常。
    public Claims parse(String token) {
        // 使用同一密钥验签，构建解析器后解析带签名的声明。
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    // 返回配置的令牌有效秒数，供登录响应告知客户端。
    public long expirationSeconds() {
        // 返回构造时已经校验过的有效期。
        return expirationSeconds;
    }
}
