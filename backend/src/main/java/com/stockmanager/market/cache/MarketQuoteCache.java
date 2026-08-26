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
 * Short-lived QMT quote cache. Redis is an acceleration layer only: a cache
 * miss or Redis failure always falls back to the internal QMT service.
 */
@Component
public class MarketQuoteCache {
    private static final String PREFIX = "stock:live:quote:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public MarketQuoteCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                            @Value("${app.market.quote-cache-ttl-ms:1000}") long ttlMs) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofMillis(Math.max(100, ttlMs));
    }

    public Optional<Map<String, Object>> get(String symbol) {
        try {
            String value = redisTemplate.opsForValue().get(key(symbol));
            if (value == null || value.isBlank()) return Optional.empty();
            return Optional.of(objectMapper.readValue(value, new TypeReference<>() {}));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void put(String symbol, Map<String, Object> quote) {
        try {
            redisTemplate.opsForValue().set(key(symbol), objectMapper.writeValueAsString(quote), ttl);
        } catch (Exception ignored) {
            // Redis is never allowed to make the manual trading screen unavailable.
        }
    }

    private String key(String symbol) {
        return PREFIX + symbol.trim().toUpperCase();
    }
}
