package com.msd.drama.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按策略维护 Caffeine L1 缓存。
 */
@Component
public class L1CacheRegistry {

    // 缓存配置，提供策略 TTL 和最大条目数
    private final CacheProperties properties;
    // 策略名到 Caffeine 缓存的映射
    private final ConcurrentMap<String, Cache<String, CachedValue>> caches = new ConcurrentHashMap<>();

    /**
     * 注入缓存配置。
     */
    public L1CacheRegistry(CacheProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取或创建指定策略的本地缓存。
     */
    public Cache<String, CachedValue> cache(String policyName) {
        // 首次访问时按策略 TTL 懒创建缓存实例
        return caches.computeIfAbsent(policyName, name -> {
            CacheProperties.Policy policy = properties.policy(name);
            return Caffeine.newBuilder()
                    .maximumSize(properties.getMaxSize())
                    .expireAfterWrite(policy.getL1Ttl())
                    .recordStats()
                    .build();
        });
    }
}
