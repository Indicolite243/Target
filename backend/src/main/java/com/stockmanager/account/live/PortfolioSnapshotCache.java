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

/**
 * 实时组合快照的 Redis 访问边界。
 *
 * <p>Controller 和业务 Service 不直接拼接 Redis key，所有 key、TTL、序列化、降级和
 * 锁释放规则集中在这里，避免不同调用方出现 key 不一致或遗漏过期时间。</p>
 */
@Component
public class PortfolioSnapshotCache {
    /** Redis 故障和 JSON 契约错误只记录摘要，由上层统一执行 MySQL 降级。 */
    private static final Logger log = LoggerFactory.getLogger(PortfolioSnapshotCache.class);
    /**
     * 原子比较并删除锁的 Lua 脚本。
     * Redis 在单线程中执行脚本，因此“读 token + 判断 + 删除”之间不会被其他客户端插入操作。
     */
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            // 只有 value 仍等于当前请求持有的 token 时才允许删除锁。
            //lua
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** 字符串 Redis 客户端；快照统一序列化为 JSON，便于跨语言排查。 */
    private final StringRedisTemplate redisTemplate;
    /** 使用 Spring 统一配置的 Jackson 规则编解码不可变快照 record。 */
    private final ObjectMapper objectMapper;
    /** 热快照的短过期时间，确保 QMT 停止采集后页面及时进入离线模式。 */
    private final Duration snapshotTtl;
    /** Redis 不可用时的进程内临时版本号，仅服务前端“是否变化”判断。 */
    private final AtomicLong fallbackVersion = new AtomicLong(System.currentTimeMillis());

    /** 注入 Redis、JSON 编解码器和实时快照 TTL。 */
    public PortfolioSnapshotCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                  @Value("${app.portfolio-live.redis-ttl-seconds:10}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.snapshotTtl = Duration.ofSeconds(Math.max(2, ttlSeconds));
    }

    /** 读取账户实时组合快照；缓存缺失或 JSON 损坏时返回 empty。 */
    public Optional<CurrentPortfolioSnapshot> get(Long accountId) {
        // Redis 故障、空值或 JSON 损坏都转换为空 Optional，由上层统一走 MySQL 降级。
        try {
            String value = redisTemplate.opsForValue().get(snapshotKey(accountId));
            if (value == null || value.isBlank()) return Optional.empty();
            // 反序列化后得到一整个版本的账户与持仓，不需要分别读取多个 key。
            return Optional.of(objectMapper.readValue(value, CurrentPortfolioSnapshot.class));
        } catch (DataAccessException ex) {
            log.warn("Redis读取实时组合失败，accountId={}", accountId);
            return Optional.empty();
        } catch (JsonProcessingException ex) {
            log.warn("Redis实时组合JSON无效，accountId={}", accountId);
            return Optional.empty();
        }
    }

    /** 原子写入账户实时快照并设置 TTL。 */
    public void put(CurrentPortfolioSnapshot snapshot) {
        // 实时快照是可重建数据，因此设置短 TTL；过期后宁可降级，也不展示永不过期的假实时数据。
        try {
            // SET key value TTL 是一次模板调用，快照不会短暂处于“已写入但未设置过期”的状态。
            redisTemplate.opsForValue().set(snapshotKey(Long.valueOf(snapshot.accountId())),
                    objectMapper.writeValueAsString(snapshot), snapshotTtl);
        } catch (DataAccessException | JsonProcessingException ex) {
            log.warn("Redis写入实时组合失败，accountId={}", snapshot.accountId());
        }
    }

    /** 递增并返回账户数据版本，用于前端判断整套数据是否发生变化。 */
    public long nextDataVersion(Long accountId) {
        // Redis INCR 让同一账户的版本单调递增；Redis 暂时不可用时使用本地兜底值，
        // 版本只用于前端刷新判断，不承担订单等业务事实的唯一性。
        try {
            Long value = redisTemplate.opsForValue().increment(versionKey(accountId));
            return value == null ? fallbackVersion.incrementAndGet() : value;
        } catch (DataAccessException ex) {
            // Redis 恢复后版本值可能换一条序列，但前端只比较是否变化，不把它当业务主键。
            return fallbackVersion.incrementAndGet();
        }
    }

    /** 使用随机令牌尝试获取账户级刷新锁，避免自动和手动刷新重叠。 */
    public String tryAcquireRefreshLock(Long accountId, Duration ttl) {
        // 每次请求创建不可预测 token，释放锁时可以确认自己仍是锁的持有者。
        String token = UUID.randomUUID().toString();
        try {
            // NX + TTL 同时保证“只有一个持有者”和“异常退出后最终自动释放”。
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey(accountId), token, ttl))
                    ? token : null;
        } catch (DataAccessException ex) {
            log.warn("Redis实时组合锁不可用，accountId={}", accountId);
            return null;
        }
    }

    /** 仅当令牌仍匹配时释放刷新锁，避免误删其他线程续期后的锁。 */
    public void releaseRefreshLock(Long accountId, String token) {
        if (token == null) return;
        try {
            // 不能直接 delete(lockKey)：旧请求可能在 TTL 到期后仍然返回，此时锁已经属于新请求。
            redisTemplate.execute(COMPARE_AND_DELETE, List.of(lockKey(accountId)), token);
        } catch (DataAccessException ex) {
            log.warn("Redis实时组合锁释放失败，accountId={}", accountId);
        }
    }

    /** 实时组合单 key；账户和持仓以同一个 JSON 文档保持版本一致性。 */
    private String snapshotKey(Long accountId) {
        return "stock:live:portfolio:" + accountId;
    }

    /** Redis INCR 使用的账户级数据版本 key。 */
    private String versionKey(Long accountId) {
        return "stock:live:portfolio:version:" + accountId;
    }

    /** QMT 采集分布式锁 key，同一账户在集群内只允许一个采集者。 */
    private String lockKey(Long accountId) {
        return "stock:lock:collect:" + accountId;
    }
}
