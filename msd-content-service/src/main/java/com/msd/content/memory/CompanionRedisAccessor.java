package com.msd.content.memory;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 非阻塞获取 RedissonClient，Redis 不可用时直接返回 null。
 *
 * <p>首次建连失败后进入 15 秒退避，避免聊天请求反复等待连接超时。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionRedisAccessor {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CompanionRedisAccessor.class);
    /** 建连失败后的退避时间（毫秒）。 */
    private static final long FAILURE_BACKOFF_MS = 15_000;

    // 懒加载的 RedissonClient 提供者
    private final ObjectProvider<RedissonClient> clientProvider;

    // 当前可用客户端；未就绪时为 null
    private volatile RedissonClient client;
    // 下次允许重试的时间戳
    private volatile long unavailableUntil;
    // 最近一次建连错误
    private volatile String lastError;

    /**
     * 注入 RedissonClient 提供者。
     */
    public CompanionRedisAccessor(ObjectProvider<RedissonClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    /**
     * 获取当前可用客户端；Redis 不可用时返回 null，调用方 fail-open。
     */
    public synchronized RedissonClient getOrNull() {
        // 1. 已就绪直接返回
        if (client != null) {
            return client;
        }
        // 2. 退避期内直接跳过
        if (System.currentTimeMillis() < unavailableUntil) {
            return null;
        }
        try {
            // 3. 懒加载创建客户端
            client = clientProvider.getObject();
            lastError = null;
            log.info("companion redis client ready");
            return client;
        } catch (Exception e) {
            // 4. 失败后记录原因并进入退避
            lastError = rootMessage(e);
            unavailableUntil = System.currentTimeMillis() + FAILURE_BACKOFF_MS;
            log.warn("companion redis client unavailable, retry in {}ms: {}",
                    FAILURE_BACKOFF_MS, lastError);
            return null;
        }
    }

    /**
     * 最近一次建连错误，供排障使用。
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 取最内层异常信息。
     */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
