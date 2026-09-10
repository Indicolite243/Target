// 声明 JWT 黑名单组件所属的包。
package com.stockmanager.system.auth.security;

// 引入 JWT 声明对象，用于读取 jti 和 expiration。
import io.jsonwebtoken.Claims;
// 引入读取黑名单前缀配置的注解。
import org.springframework.beans.factory.annotation.Value;
// 引入 Redis 字符串模板。
import org.springframework.data.redis.core.StringRedisTemplate;
// 引入 Spring 组件注解。
import org.springframework.stereotype.Component;

// 引入 Redis TTL 使用的时间长度类型。
import java.time.Duration;
// 引入当前时间点。
import java.time.Instant;
// 引入 JWT 的过期时间类型。
import java.util.Date;

// 使用 Redis 保存已主动注销令牌的 jti，不保存完整 JWT 原文。
@Component
public class JwtTokenBlacklist {
    // Redis 操作客户端。
    private final StringRedisTemplate redisTemplate;
    // 黑名单 Key 的统一前缀。
    private final String keyPrefix;

    // 注入 Redis 客户端和可配置的 Key 前缀。
    public JwtTokenBlacklist(StringRedisTemplate redisTemplate,
                             @Value("${app.jwt.blacklist-prefix:stock:auth:blacklist:}") String keyPrefix) {
        // 保存 Redis 客户端。
        this.redisTemplate = redisTemplate;
        // 保存黑名单 Key 前缀。
        this.keyPrefix = keyPrefix;
    }

    // 判断令牌的 jti 是否已经被主动注销。
    public boolean isRevoked(Claims claims) {
        // 只查询 jti 对应的 Redis Key；查询失败由上层认证策略处理。
        String jti = claims.getId();
        // jti 存在且 Redis 中有对应 Key 时，说明令牌已被撤销。
        return jti != null && Boolean.TRUE.equals(redisTemplate.hasKey(keyPrefix + jti));
    }

    // 将令牌 jti 写入黑名单，并设置不超过令牌剩余寿命的 TTL。
    public void revoke(Claims claims) {
        // 黑名单只需保存 jti，不需要保存可能包含敏感声明的 JWT 原文。
        String jti = claims.getId();
        // 读取令牌过期时间，以便计算 Redis Key 的存活时间。
        Date expiration = claims.getExpiration();
        // 缺少必要声明时无法安全撤销，直接结束操作。
        if (jti == null || jti.isBlank() || expiration == null) {
            // 不写入不完整的黑名单记录。
            return;
        }
        // 计算从现在到令牌过期时刻的剩余时间。
        Duration remaining = Duration.between(Instant.now(), expiration.toInstant());
        // 只有仍未过期的令牌才需要写入黑名单。
        if (!remaining.isNegative() && !remaining.isZero()) {
            // 保存标记值 1，并让 Redis 在令牌自然过期时自动删除该 Key。
            redisTemplate.opsForValue().set(keyPrefix + jti, "1", remaining);
        }
    }
}
