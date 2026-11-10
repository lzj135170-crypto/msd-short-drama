package com.msd.drama.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * L2 缓存和分布式锁抽象，方便单元测试替换 Redis。
 */
public interface CacheBackend {

    /**
     * 读取 key 对应的原始 JSON；未命中返回空 Optional。
     */
    Optional<String> get(String key);

    /**
     * 写入 key 并设置过期时间。
     */
    void set(String key, String value, Duration ttl);

    /**
     * 删除单个 key。
     */
    void delete(String key);

    /**
     * 按通配模式批量删除 key。
     */
    void deleteByPattern(String pattern);

    /**
     * 尝试获取分布式锁；wait 为最长等待时间，lease 为持锁租期。
     */
    LockHandle tryLock(String key, Duration wait, Duration lease);

    /**
     * 释放当前线程持有的锁。
     */
    void unlock(LockHandle handle);
}
