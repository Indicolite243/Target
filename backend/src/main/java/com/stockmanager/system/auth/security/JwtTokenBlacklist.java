package com.stockmanager.system.auth.security;

import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Stores only revoked token ids. The token itself never enters Redis or logs.
 * Redis is part of the required runtime infrastructure, so a failed blacklist
 * lookup must invalidate the request rather than silently accepting a logout.
 */
@Component
public class JwtTokenBlacklist {
    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public JwtTokenBlacklist(StringRedisTemplate redisTemplate,
                             @Value("${app.jwt.blacklist-prefix:stock:auth:blacklist:}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    public boolean isRevoked(Claims claims) {
        String jti = claims.getId();
        return jti != null && Boolean.TRUE.equals(redisTemplate.hasKey(keyPrefix + jti));
    }

    public void revoke(Claims claims) {
        String jti = claims.getId();
        Date expiration = claims.getExpiration();
        if (jti == null || jti.isBlank() || expiration == null) {
            return;
        }
        Duration remaining = Duration.between(Instant.now(), expiration.toInstant());
        if (!remaining.isNegative() && !remaining.isZero()) {
            redisTemplate.opsForValue().set(keyPrefix + jti, "1", remaining);
        }
    }
}
