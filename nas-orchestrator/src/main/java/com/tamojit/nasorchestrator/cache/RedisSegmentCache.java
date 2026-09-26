package com.tamojit.nasorchestrator.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(name = "cache.l2.enabled", havingValue = "true")
public class RedisSegmentCache implements SegmentCache {
    private static final String PREFIX = "seg:";
    private final RedisTemplate<String, byte[]> redisTemplate;

    public RedisSegmentCache(RedisTemplate<String, byte[]> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public byte[] get(String key) {
        return redisTemplate.opsForValue().get(PREFIX + key);
    }

    @Override
    public void put(String key, byte[] data) {
        redisTemplate.opsForValue().set(PREFIX + key, data, Duration.ofHours(6));
    }
}
