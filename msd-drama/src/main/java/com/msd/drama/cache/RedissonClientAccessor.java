package com.msd.drama.cache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 非阻塞地初始化 RedissonClient。
 *
 * <p>Redisson 建连失败时可能阻塞较久，不能让它发生在 Tomcat 请求线程上。
 * 请求线程只读取当前可用的客户端；客户端由独立守护线程创建，失败按
 * {@code msd.cache.redis.failure-backoff} 重试。</p>
 */
@Component
public class RedissonClientAccessor {

    // 建连与重试日志
    private static final Logger log = LoggerFactory.getLogger(RedissonClientAccessor.class);

    // RedissonClient Bean 提供者
    private final ObjectProvider<RedissonClient> clientProvider;
    // 缓存配置
    private final CacheProperties properties;
    // 单线程守护调度器，负责建连和失败重试
    private final ScheduledExecutorService initExecutor;
    // 防止并发重复触发初始化
    private final AtomicBoolean initializing = new AtomicBoolean(false);

    // 当前可用客户端；未就绪时为 null
    private volatile RedissonClient client;
    // 下次允许重试的时间戳
    private volatile long nextAttemptAt;
    // 最近一次建连错误
    private volatile String lastError;

    /**
     * 注入客户端提供者和缓存配置，并创建守护调度线程。
     */
    public RedissonClientAccessor(ObjectProvider<RedissonClient> clientProvider,
                                  CacheProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
        // 守护线程不阻止 JVM 退出
        this.initExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "redisson-init");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 启动后异步建连；Redis 不可用时不影响应用启动。
     */
    @PostConstruct
    public void start() {
        if (properties.isEnabled()) {
            triggerInitialize();
        }
    }

    /**
     * 当前可用客户端；未就绪时返回 null，调用方直接降级。
     */
    public RedissonClient get() {
        if (client == null) {
            triggerInitialize();
        }
        return client;
    }

    /**
     * 最近一次建连错误，供排障使用。
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 应用关闭时停止建连线程，不再安排重试。
     */
    @PreDestroy
    public void shutdown() {
        initExecutor.shutdownNow();
    }

    /**
     * 在满足退避条件时异步触发一次建连；已有初始化进行中则跳过。
     */
    private void triggerInitialize() {
        // 1. 已就绪或仍在退避期内时跳过
        if (client != null || System.currentTimeMillis() < nextAttemptAt) {
            return;
        }
        // 2. CAS 保证同一时刻只有一个初始化任务
        if (!initializing.compareAndSet(false, true)) {
            return;
        }
        try {
            // 3. 提交到守护线程执行
            initExecutor.execute(this::initialize);
        } catch (RuntimeException e) {
            // 4. 提交失败时释放标记，允许下次重试
            initializing.set(false);
            log.warn("schedule redisson init failed: {}", e.getMessage());
        }
    }

    /**
     * 异步建连；失败时按配置退避并安排下次重试。
     */
    private void initialize() {
        try {
            // 1. 懒加载创建客户端
            client = clientProvider.getObject();
            // 2. 成功后清除退避和错误信息
            nextAttemptAt = 0;
            lastError = null;
            log.info("redisson client ready");
        } catch (Exception e) {
            // 3. 失败时记录根因并设置退避截止时间
            long backoff = properties.getRedis().getFailureBackoff().toMillis();
            nextAttemptAt = System.currentTimeMillis() + backoff;
            lastError = rootMessage(e);
            log.warn("redisson client init failed, retry in {}ms: {}", backoff, lastError);
        } finally {
            // 4. 释放初始化标记；仍未就绪则安排重试
            initializing.set(false);
            if (client == null) {
                scheduleRetry();
            }
        }
    }

    /**
     * 按退避截止时间安排下一次重试，至少延迟 1 秒。
     */
    private void scheduleRetry() {
        long delay = Math.max(1_000L, nextAttemptAt - System.currentTimeMillis() + 100L);
        try {
            initExecutor.schedule(this::triggerInitialize, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.debug("schedule redisson retry failed: {}", e.getMessage());
        }
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
