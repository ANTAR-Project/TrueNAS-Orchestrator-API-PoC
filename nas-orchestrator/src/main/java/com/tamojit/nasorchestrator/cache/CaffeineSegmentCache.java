package com.tamojit.nasorchestrator.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CaffeineSegmentCache implements SegmentCache {
    private final Cache<String, byte[]> cache;

    public CaffeineSegmentCache(
        @Value("${cache.l1.max-weight-bytes:268435456}") long maxWeightBytes
    ) {
        this.cache = Caffeine.newBuilder()
            .maximumWeight(maxWeightBytes)
            .weigher((String k, byte[] v) -> v.length)
            .expireAfterAccess(Duration.ofMinutes(30))
            .recordStats()
            .build();
    }

    @Override
    public byte[] get(String key) {
        return cache.getIfPresent(key);
    }

    @Override
    public void put(String key, byte[] data) {
        cache.put(key, data);
    }
}
