package com.msd.content.index;

import com.msd.common.event.DramaChangedEvent;
import com.msd.content.config.RagProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按 dramaId 去抖的增量索引调度器。
 *
 * <p>同一短剧在去抖窗口内只处理最后一条事件；索引失败按配置重试，
 * 重试仍失败时记录日志，由全量重建兜底。事件较旧时直接跳过，避免覆盖更新状态。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DramaIndexUpdateScheduler {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DramaIndexUpdateScheduler.class);

    // 增量索引服务
    private final IncrementalIndexService indexService;
    // 去抖和重试配置
    private final RagProperties properties;
    // 延迟执行线程池
    private final ScheduledExecutorService executor;
    // 每个短剧待执行的任务
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    // 每个短剧最近一次事件时间
    private final Map<Long, Long> latestOccurredAt = new ConcurrentHashMap<>();

    /**
     * Spring 使用的构造器，按配置创建守护线程池。
     */
    @Autowired
    public DramaIndexUpdateScheduler(IncrementalIndexService indexService,
                                     RagProperties properties) {
        this(indexService, properties, newScheduler(
                Math.max(properties.getIncremental().getConcurrency(), 1)));
    }

    /**
     * 测试使用的构造器，允许注入线程池。
     */
    DramaIndexUpdateScheduler(IncrementalIndexService indexService,
                              RagProperties properties,
                              ScheduledExecutorService executor) {
        this.indexService = indexService;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * 调度一条短剧变更；同剧旧任务会被取消。
     */
    public void schedule(DramaChangedEvent event) {
        // 1. 校验事件
        if (event == null || event.dramaId() == null || event.dramaId() <= 0) {
            return;
        }
        Long dramaId = event.dramaId();
        // 2. 晚到的旧事件不调度，也不取消已排队的新事件
        Long latest = latestOccurredAt.get(dramaId);
        if (latest != null && event.occurredAt() < latest) {
            log.debug("stale drama changed event ignored on schedule: dramaId={}, eventId={}",
                    dramaId, event.eventId());
            return;
        }
        latestOccurredAt.put(dramaId, event.occurredAt());

        // 3. 去抖窗口内只保留最后一条事件
        long debounceMs = Math.max(properties.getIncremental().getDebounceMs(), 0);
        ScheduledFuture<?> next = executor.schedule(
                () -> execute(event, 1), debounceMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = pending.put(dramaId, next);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /**
     * 执行索引；失败按配置重试，旧事件直接跳过。
     */
    private void execute(DramaChangedEvent event, int attempt) {
        Long dramaId = event.dramaId();
        // 1. 已有更新事件时跳过，避免旧事件覆盖新状态
        Long latest = latestOccurredAt.get(dramaId);
        if (latest != null && event.occurredAt() < latest) {
            log.debug("stale drama changed event skipped: dramaId={}, eventId={}",
                    dramaId, event.eventId());
            return;
        }
        // 2. 清理待执行标记
        pending.remove(dramaId);

        try {
            indexService.apply(event);
        } catch (Exception e) {
            int maxAttempts = Math.max(properties.getIncremental().getRetryAttempts(), 1);
            if (attempt < maxAttempts) {
                log.warn("incremental index failed, retry {}/{}: dramaId={}, error={}",
                        attempt, maxAttempts, dramaId, rootMessage(e));
                long delayMs = Math.max(properties.getIncremental().getRetryDelayMs(), 0);
                executor.schedule(() -> execute(event, attempt + 1),
                        delayMs, TimeUnit.MILLISECONDS);
                return;
            }
            // 3. 重试耗尽后只记录错误，等待下一次事件或全量重建
            log.error("incremental index failed after retries: dramaId={}, eventId={}, error={}",
                    dramaId, event.eventId(), rootMessage(e));
        }
    }

    /**
     * 创建带命名守护线程的调度器。
     */
    private static ScheduledExecutorService newScheduler(int threads) {
        AtomicInteger counter = new AtomicInteger(1);
        return Executors.newScheduledThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "rag-index-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 应用关闭时停止调度。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
