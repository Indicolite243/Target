package com.stockmanager.account.live;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Redis access is isolated here so controllers never manipulate cache keys directly. */
@Component
public class PortfolioSnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(PortfolioSnapshotCache.class);
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration snapshotTtl;
    private final AtomicLong fallbackVersion = new AtomicLong(System.currentTimeMillis());

    public PortfolioSnapshotCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                  @Value("${app.portfolio-live.redis-ttl-seconds:10}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.snapshotTtl = Duration.ofSeconds(Math.max(2, ttlSeconds));
    }

    public Optional<CurrentPortfolioSnapshot> get(Long accountId) {
        try {
            String value = redisTemplate.opsForValue().get(snapshotKey(accountId));
            if (value == null || value.isBlank()) return Optional.empty();
            return Optional.of(objectMapper.readValue(value, CurrentPortfolioSnapshot.class));
        } catch (DataAccessException ex) {
            log.warn("Redis读取实时组合失败，accountId={}", accountId);
            return Optional.empty();
        } catch (JsonProcessingException ex) {
            log.warn("Redis实时组合JSON无效，accountId={}", accountId);
            return Optional.empty();
        }
    }

    public void put(CurrentPortfolioSnapshot snapshot) {
        try {
            redisTemplate.opsForValue().set(snapshotKey(Long.valueOf(snapshot.accountId())),
                    objectMapper.writeValueAsString(snapshot), snapshotTtl);
        } catch (DataAccessException | JsonProcessingException ex) {
            log.warn("Redis写入实时组合失败，accountId={}", snapshot.accountId());
        }
    }

    public long nextDataVersion(Long accountId) {
        try {
            Long value = redisTemplate.opsForValue().increment(versionKey(accountId));
            return value == null ? fallbackVersion.incrementAndGet() : value;
        } catch (DataAccessException ex) {
            return fallbackVersion.incrementAndGet();
        }
    }

    public String tryAcquireRefreshLock(Long accountId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey(accountId), token, ttl))
                    ? token : null;
        } catch (DataAccessException ex) {
            log.warn("Redis实时组合锁不可用，accountId={}", accountId);
            return null;
        }
    }

    public void releaseRefreshLock(Long accountId, String token) {
        if (token == null) return;
        try {
            redisTemplate.execute(COMPARE_AND_DELETE, List.of(lockKey(accountId)), token);
        } catch (DataAccessException ex) {
            log.warn("Redis实时组合锁释放失败，accountId={}", accountId);
        }
    }

    private String snapshotKey(Long accountId) {
        return "stock:live:portfolio:" + accountId;
    }

    private String versionKey(Long accountId) {
        return "stock:live:portfolio:version:" + accountId;
    }

    private String lockKey(Long accountId) {
        return "stock:lock:collect:" + accountId;
    }
}
