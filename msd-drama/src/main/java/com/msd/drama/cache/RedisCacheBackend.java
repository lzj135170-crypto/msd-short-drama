package com.msd.drama.cache;

import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 实现的 L2 缓存和分布式锁。
 *
 * <p>Redis 不可用时只记录日志并返回降级结果，不影响业务查询。</p>
 */
@Component
public class RedisCacheBackend implements CacheBackend {

    // Redis 降级与异常日志
    private static final Logger log = LoggerFactory.getLogger(RedisCacheBackend.class);

    // 非阻塞 Redisson 客户端访问器
    private final RedissonClientAccessor clientAccessor;
    // 缓存配置，提供失败退避时间
    private final CacheProperties properties;

    // 退避截止时间戳；当前时间小于它时直接降级
    private volatile long unavailableUntil;
    // 最近一次 Redis 错误信息
    private volatile String lastError;

    /**
     * 注入 Redisson 客户端访问器和缓存配置。
     */
    public RedisCacheBackend(RedissonClientAccessor clientAccessor,
                             CacheProperties properties) {
        this.clientAccessor = clientAccessor;
        this.properties = properties;
    }

    /**
     * 读取原始 JSON；Redis 不可用或读取异常时返回空，由上层回源数据库。
     */
    @Override
    public Optional<String> get(String key) {
        // 1. 处于退避期直接判定未命中
        if (isUnavailable()) {
            return Optional.empty();
        }
        try {
            // 2. 客户端尚未就绪时同样降级
            RedissonClient client = client();
            if (client == null) {
                return Optional.empty();
            }
            // 3. 读取成功后清除退避标记
            RBucket<String> bucket = client.getBucket(key, StringCodec.INSTANCE);
            String value = bucket.get();
            unavailableUntil = 0;
            return Optional.ofNullable(value);
        } catch (Exception e) {
            // 4. 记录异常并进入退避窗口
            markUnavailable(e);
            return Optional.empty();
        }
    }

    /**
     * 写入原始 JSON 并设置 TTL；异常时只标记不可用，不向调用方抛出。
     */
    @Override
    public void set(String key, String value, Duration ttl) {
        // 1. 处于退避期或客户端未就绪时跳过写入
        if (isUnavailable()) {
            return;
        }
        try {
            RedissonClient client = client();
            if (client == null) {
                return;
            }
            // 2. 写入成功后清除退避标记
            RBucket<String> bucket = client.getBucket(key, StringCodec.INSTANCE);
            bucket.set(value, ttl);
            unavailableUntil = 0;
        } catch (Exception e) {
            // 3. 失败时进入退避窗口
            markUnavailable(e);
        }
    }

    /**
     * 删除单个 key；删除失败只标记不可用。
     */
    @Override
    public void delete(String key) {
        // 1. 处于退避期或客户端未就绪时跳过删除
        if (isUnavailable()) {
            return;
        }
        try {
            RedissonClient client = client();
            if (client == null) {
                return;
            }
            // 2. 删除成功后清除退避标记
            client.getBucket(key, StringCodec.INSTANCE).delete();
            unavailableUntil = 0;
        } catch (Exception e) {
            // 3. 失败时进入退避窗口
            markUnavailable(e);
        }
    }

    /**
     * 按通配模式批量删除 key；删除失败只标记不可用。
     */
    @Override
    public void deleteByPattern(String pattern) {
        // 1. 处于退避期或客户端未就绪时跳过删除
        if (isUnavailable()) {
            return;
        }
        try {
            RedissonClient client = client();
            if (client == null) {
                return;
            }
            // 2. 批量删除成功后清除退避标记
            client.getKeys().deleteByPattern(pattern);
            unavailableUntil = 0;
        } catch (Exception e) {
            // 3. 失败时进入退避窗口
            markUnavailable(e);
        }
    }

    /**
     * 尝试获取 Redisson 锁：成功返回持有句柄，被占用返回 BUSY，异常返回 UNAVAILABLE。
     */
    @Override
    public LockHandle tryLock(String key, Duration wait, Duration lease) {
        // 1. 处于退避期或客户端未就绪时直接判定不可用
        if (isUnavailable()) {
            return LockHandle.unavailable();
        }
        try {
            RedissonClient client = client();
            if (client == null) {
                return LockHandle.unavailable();
            }
            // 2. 按等待时间和租期尝试加锁
            RLock lock = client.getLock(key);
            boolean acquired = lock.tryLock(wait.toMillis(), lease.toMillis(), TimeUnit.MILLISECONDS);
            unavailableUntil = 0;
            return acquired ? LockHandle.acquired(lock) : LockHandle.busy();
        } catch (InterruptedException e) {
            // 3. 中断时恢复中断标记，交给上层终止流程
            Thread.currentThread().interrupt();
            return LockHandle.unavailable();
        } catch (Exception e) {
            // 4. 其他异常进入退避窗口
            markUnavailable(e);
            return LockHandle.unavailable();
        }
    }

    /**
     * 释放当前线程持有的锁；非本线程持有或句柄为空时静默跳过。
     */
    @Override
    public void unlock(LockHandle handle) {
        // 1. 未成功加锁的句柄无需释放
        if (handle == null || handle.lock() == null) {
            return;
        }
        try {
            // 2. 只释放当前线程持有的锁，避免误删其他实例的锁
            if (handle.lock().isHeldByCurrentThread()) {
                handle.lock().unlock();
            }
        } catch (Exception e) {
            log.debug("release cache lock failed: {}", e.getMessage());
        }
    }

    /**
     * 最近一次 Redis 错误，供状态接口或排障使用。
     */
    public String getLastError() {
        return lastError != null ? lastError : clientAccessor.getLastError();
    }

    /**
     * 取当前可用客户端；尚未就绪时返回 null。
     */
    private RedissonClient client() {
        return clientAccessor.get();
    }

    /**
     * 是否处于 Redis 故障退避期。
     */
    private boolean isUnavailable() {
        return System.currentTimeMillis() < unavailableUntil;
    }

    /**
     * 记录错误根因并开启退避窗口，期间所有缓存操作直接降级。
     */
    private void markUnavailable(Exception e) {
        lastError = rootMessage(e);
        unavailableUntil = System.currentTimeMillis() + properties.getRedis().getFailureBackoff().toMillis();
        log.warn("redis cache unavailable, fallback to DB: {}", lastError);
    }

    /**
     * 取最深层异常的简短信息，避免日志只显示包装异常。
     */
    private String rootMessage(Throwable throwable) {
        // 沿 cause 链向下找到根因
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
