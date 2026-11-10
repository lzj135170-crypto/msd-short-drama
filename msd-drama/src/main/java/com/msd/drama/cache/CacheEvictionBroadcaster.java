package com.msd.drama.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L1 缓存跨实例失效广播。
 *
 * <p>删除 L2 后向 Redis Topic 发布失效消息，其他实例只清理本地 Caffeine，
 * 不再重复删除 L2 或再次广播，避免消息回环。</p>
 */
@Component
public class CacheEvictionBroadcaster {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CacheEvictionBroadcaster.class);

    /** 广播 Topic。 */
    private static final String TOPIC = "msd:drama:cache:evict";
    /** 消息字段分隔符。 */
    private static final String SEPARATOR = "\u0001";

    // 缓存配置
    private final CacheProperties properties;
    // Redisson 客户端访问器
    private final RedissonClientAccessor clientAccessor;
    // 本地 L1 缓存注册表
    private final L1CacheRegistry l1Registry;

    // 已订阅的客户端，避免重复注册监听器
    private volatile RedissonClient subscribedClient;

    /**
     * 注入配置、客户端访问器和 L1 注册表。
     */
    public CacheEvictionBroadcaster(CacheProperties properties,
                                    RedissonClientAccessor clientAccessor,
                                    L1CacheRegistry l1Registry) {
        this.properties = properties;
        this.clientAccessor = clientAccessor;
        this.l1Registry = l1Registry;
    }

    /**
     * 发布失效消息；广播关闭或 Redis 不可用时静默跳过。
     */
    public void publish(String cacheName, String key, boolean allEntries) {
        // 1. 未开启广播时不做任何事
        if (!properties.getEvict().isBroadcastEnabled()) {
            return;
        }
        // 2. 客户端未就绪时跳过，等待下次写路径再试
        RedissonClient client = clientAccessor.get();
        if (client == null) {
            return;
        }
        try {
            // 3. 首次使用时注册监听器
            ensureSubscribed(client);
            client.getTopic(TOPIC, StringCodec.INSTANCE)
                    .publish(cacheName + SEPARATOR + key + SEPARATOR + allEntries);
        } catch (Exception e) {
            log.warn("cache eviction broadcast failed: cache={}, key={}, error={}",
                    cacheName, key, e.getMessage());
        }
    }

    /**
     * 注册本地失效监听器，同一客户端只注册一次。
     */
    private void ensureSubscribed(RedissonClient client) {
        if (subscribedClient == client) {
            return;
        }
        synchronized (this) {
            if (subscribedClient == client) {
                return;
            }
            RTopic topic = client.getTopic(TOPIC, StringCodec.INSTANCE);
            topic.addListener(String.class, (channel, message) -> invalidateLocal(message));
            subscribedClient = client;
            log.info("cache eviction broadcaster subscribed: topic={}", TOPIC);
        }
    }

    /**
     * 只清理本地 L1，不触碰 L2，避免多实例消息回环。
     */
    private void invalidateLocal(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String[] parts = message.split(SEPARATOR, -1);
        if (parts.length != 3) {
            log.warn("invalid cache eviction message: {}", message);
            return;
        }
        String cacheName = parts[0];
        String key = parts[1];
        boolean allEntries = Boolean.parseBoolean(parts[2]);
        Cache<String, CachedValue> cache = l1Registry.cache(cacheName);
        if (allEntries) {
            cache.invalidateAll();
        } else {
            cache.invalidate(CacheKeys.local(cacheName, key));
        }
    }
}
