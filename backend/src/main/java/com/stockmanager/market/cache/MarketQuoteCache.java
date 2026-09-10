package com.stockmanager.market.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * QMT实时行情的Redis短缓存组件。
 *
 * <p>Redis在这里仅是性能加速层，不是行情事实来源：缓存未命中、JSON损坏或Redis连接异常时，
 * get方法统一返回empty，MarketService会继续访问FastAPI/QMT；缓存写入失败也不能导致交易页面不可用。</p>
 *
 * <p>缓存按单只证券拆分Key并设置毫秒级TTL，使同一秒内多个页面组件可以共享行情，
 * 同时避免长期返回过时价格。</p>
 */
@Component
public class MarketQuoteCache {
    /** Redis Key统一前缀，完整格式为stock:live:quote:{大写证券代码}。 */
    private static final String PREFIX = "stock:live:quote:";

    /** 只操作字符串Key/Value的Redis模板，行情对象会先序列化为JSON文本。 */
    private final StringRedisTemplate redisTemplate;
    /** 行情Map与JSON字符串之间的编解码器。 */
    private final ObjectMapper objectMapper;
    /** 单条行情缓存有效期；默认1000毫秒，最低强制100毫秒。 */
    private final Duration ttl;

    /**
     * 注入Redis、JSON编解码器及毫秒级行情缓存TTL。
     *
     * @param redisTemplate Spring Redis字符串操作模板
     * @param objectMapper JSON编解码器
     * @param ttlMs 配置项app.market.quote-cache-ttl-ms，默认1000毫秒
     */
    public MarketQuoteCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                            @Value("${app.market.quote-cache-ttl-ms:1000}") long ttlMs) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        // 即使配置错误地给出0或负数，也至少缓存100毫秒，避免瞬时重复请求直接击穿QMT。
        this.ttl = Duration.ofMillis(Math.max(100, ttlMs));
    }

    /**
     * 读取单个证券的缓存行情。
     *
     * @param symbol 证券代码，方法内部会去空格并转成大写Key
     * @return 命中且JSON有效时返回行情Map；缺失、过期、损坏或Redis异常时返回empty
     */
    public Optional<Map<String, Object>> get(String symbol) {
        try {
            // 使用规范化Key读取JSON字符串；TTL到期后Redis会自动返回null。
            String value = redisTemplate.opsForValue().get(key(symbol));
            // 缺失、过期或异常空值统一视为缓存未命中。
            if (value == null || value.isBlank()) return Optional.empty();

            // TypeReference保留Map<String,Object>泛型信息，把JSON对象恢复为行情Map。
            return Optional.of(objectMapper.readValue(value, new TypeReference<>() {}));
        } catch (Exception ignored) {
            // Redis断开、超时或历史脏JSON都不能中断行情请求；Service随后会回源QMT。
            return Optional.empty();
        }
    }

    /**
     * 写入单个证券行情并设置短TTL。
     *
     * @param symbol 证券代码
     * @param quote FastAPI/QMT返回并完成字段标准化的行情对象
     */
    public void put(String symbol, Map<String, Object> quote) {
        try {
            // set操作一次性写入JSON和TTL，避免出现写入成功但没有过期时间的永久旧行情。
            redisTemplate.opsForValue().set(key(symbol), objectMapper.writeValueAsString(quote), ttl);
        } catch (Exception ignored) {
            // Redis只是加速层；写入失败时当前请求仍返回QMT数据，后续请求继续尝试回源。
        }
    }

    /** 把任意大小写证券代码转换为唯一、稳定的Redis Key。 */
    private String key(String symbol) {
        // 例如" 600000.sh "最终转换为stock:live:quote:600000.SH。
        return PREFIX + symbol.trim().toUpperCase();
    }
}
