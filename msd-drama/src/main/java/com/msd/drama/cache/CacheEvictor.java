package com.msd.drama.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 缓存失效器：本地 L1 + Redis L2，支持事务提交后删除、延迟双删和防抖。
 */
@Component
public class CacheEvictor {

    // 缓存失效日志
    private static final Logger log = LoggerFactory.getLogger(CacheEvictor.class);

    // 缓存配置
    private final CacheProperties properties;
    // 本地 L1 缓存注册表
    private final L1CacheRegistry l1Registry;
    // Redis L2 缓存与分布式锁
    private final CacheBackend backend;
    // 延迟删除和防抖调度器
    private final TaskScheduler scheduler;
    // 跨实例 L1 失效广播
    private final CacheEvictionBroadcaster broadcaster;
    // 防抖任务表：key 为防抖标识，value 为等待执行的任务
    private final ConcurrentMap<String, ScheduledFuture<?>> debounceTasks = new ConcurrentHashMap<>();

    /**
     * 注入缓存配置、L1 注册表、Redis 后端、调度器和广播器。
     */
    public CacheEvictor(CacheProperties properties,
                        L1CacheRegistry l1Registry,
                        CacheBackend backend,
                        TaskScheduler scheduler,
                        CacheEvictionBroadcaster broadcaster) {
        this.properties = properties;
        this.l1Registry = l1Registry;
        this.backend = backend;
        this.scheduler = scheduler;
        this.broadcaster = broadcaster;
    }

    /**
     * 立即删除 L1 和 L2。
     */
    public void evictNow(String cacheName, String key, boolean allEntries) {
        Cache<String, CachedValue> local = l1Registry.cache(cacheName);
        try {
            // 1. 按 allEntries 选择清空整个命名空间还是只删单个 key
            if (allEntries) {
                local.invalidateAll();
                backend.deleteByPattern(CacheKeys.DATA_PREFIX + cacheName + ":*");
            } else {
                local.invalidate(CacheKeys.local(cacheName, key));
                backend.delete(CacheKeys.data(cacheName, key));
            }
            // 3. 通知其他实例清理各自的 L1
            broadcaster.publish(cacheName, key, allEntries);
            log.debug("cache evicted: cache={}, key={}, allEntries={}", cacheName, key, allEntries);
        } catch (Exception e) {
            // 2. 删除失败只记录日志，缓存异常不能阻断业务
            log.warn("cache evict failed: cache={}, key={}: {}", cacheName, key, e.getMessage());
        }
    }

    /**
     * 事务提交后立即删除，再延迟删除第二次。
     */
    public void evictAfterCommit(String cacheName, String key, boolean allEntries, long delayMillis) {
        afterCommit(() -> {
            // 1. 事务提交后立即删除一次
            evictNow(cacheName, key, allEntries);
            // 2. 再安排延迟删除，覆盖并发读回填的旧值
            schedule(() -> evictNow(cacheName, key, allEntries), delay(delayMillis));
        });
    }

    /**
     * 事务提交后只安排延迟删除；用于 @MultiLevelCacheEvict 已做写前删除的场景。
     */
    public void scheduleDelayedAfterCommit(String cacheName, String key, boolean allEntries, long delayMillis) {
        // 写前已删除过一次，这里只安排事务提交后的延迟删除
        afterCommit(() -> schedule(() -> evictNow(cacheName, key, allEntries), delay(delayMillis)));
    }

    /**
     * 剧集解锁后失效详情；解锁计数不影响首页，只删 detail。
     */
    public void evictDetailAfterCommit(Long dramaId) {
        // 1. 没有剧 ID 时无需失效
        if (dramaId == null) {
            return;
        }
        // 2. 只删 detail 单 key，解锁计数不影响首页缓存
        evictAfterCommit("detail", String.valueOf(dramaId), false,
                properties.getEvict().getDelay().toMillis());
    }

    /**
     * 互动计数防抖失效首页；避免每条 Kafka 消息都打穿缓存。
     */
    public void evictHomeDebounced() {
        // 1. 事务提交后进入防抖窗口，窗口内多次调用只保留一次
        afterCommit(() -> debounce("home", properties.getEvict().getHomeDebounce(),
                () -> {
                    // 2. 窗口结束后立即删除，再安排延迟删除
                    evictNow("home", "v1", false);
                    schedule(() -> evictNow("home", "v1", false),
                            properties.getEvict().getDelay());
                }));
    }

    /**
     * 互动计数防抖失效详情。
     */
    public void evictDetailDebounced(Long dramaId) {
        // 1. 没有剧 ID 时无需失效
        if (dramaId == null) {
            return;
        }
        String key = String.valueOf(dramaId);
        // 2. 按剧 ID 防抖，窗口结束后删除并延迟再删
        afterCommit(() -> debounce("detail:" + key, properties.getEvict().getDetailDebounce(),
                () -> {
                    evictNow("detail", key, false);
                    schedule(() -> evictNow("detail", key, false),
                            properties.getEvict().getDelay());
                }));
    }

    /**
     * 窗口期内同一 key 只保留一个待执行任务，重复调用直接复用已有任务。
     */
    private void debounce(String key, Duration window, Runnable action) {
        debounceTasks.computeIfAbsent(key, ignored -> {
            try {
                return scheduler.schedule(() -> {
                    debounceTasks.remove(key);
                    action.run();
                }, Instant.now().plus(window));
            } catch (Exception e) {
                log.warn("schedule cache debounce failed: {}: {}", key, e.getMessage());
                return null;
            }
        });
    }

    /**
     * 延迟执行删除任务；调度或执行异常只记录日志。
     */
    private void schedule(Runnable action, Duration delay) {
        try {
            scheduler.schedule(() -> {
                try {
                    action.run();
                } catch (Exception e) {
                    log.warn("delayed cache evict failed: {}", e.getMessage());
                }
            }, Instant.now().plus(delay));
        } catch (Exception e) {
            log.warn("schedule cache evict failed: {}", e.getMessage());
        }
    }

    /**
     * 有事务时注册到提交后回调，无事务时立即执行。
     */
    private void afterCommit(Runnable action) {
        // 1. 事务同步已激活：挂到 afterCommit 阶段执行
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 匿名同步器：只在事务提交成功后执行缓存失效
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 事务提交后执行注册的回调。
                 */
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        // 2. 无事务上下文：直接执行
        action.run();
    }

    /**
     * 传入毫秒数优先；非正数时回退到全局延迟配置。
     */
    private Duration delay(long delayMillis) {
        return delayMillis > 0 ? Duration.ofMillis(delayMillis) : properties.getEvict().getDelay();
    }
}
