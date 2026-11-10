package com.msd.drama.cache;

/**
 * 缓存 key 统一规则，避免不同调用方拼出不一致的 key。
 */
public final class CacheKeys {

    /** Redis 数据 key 统一前缀 */
    public static final String DATA_PREFIX = "msd:drama:cache:";
    /** Redisson 锁 key 统一前缀 */
    public static final String LOCK_PREFIX = "msd:drama:lock:cache:";

    /**
     * 工具类禁止实例化。
     */
    private CacheKeys() {
    }

    /**
     * Redis 数据 key。
     */
    public static String data(String cacheName, String key) {
        return DATA_PREFIX + cacheName + ":" + key;
    }

    /**
     * Redisson 锁 key。
     */
    public static String lock(String cacheName, String key) {
        return LOCK_PREFIX + cacheName + ":" + key;
    }

    /**
     * Caffeine 本地 key。
     */
    public static String local(String cacheName, String key) {
        return cacheName + ":" + key;
    }
}
