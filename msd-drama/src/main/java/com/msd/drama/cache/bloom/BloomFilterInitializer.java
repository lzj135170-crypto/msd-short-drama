package com.msd.drama.cache.bloom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 启动时异步检查布隆过滤器；缺失则重建，不阻塞服务启动。
 */
@Component
@ConditionalOnProperty(prefix = "msd.cache.bloom", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BloomFilterInitializer {

    // 启动重建日志
    private static final Logger log = LoggerFactory.getLogger(BloomFilterInitializer.class);

    // 布隆过滤器服务
    private final DramaBloomFilterService bloomFilterService;
    // 启动任务调度器
    private final TaskScheduler scheduler;

    /**
     * 注入布隆过滤器服务和调度器。
     */
    public BloomFilterInitializer(DramaBloomFilterService bloomFilterService,
                                  TaskScheduler scheduler) {
        this.bloomFilterService = bloomFilterService;
        this.scheduler = scheduler;
    }

    /**
     * 应用就绪后延迟 1 秒异步检查，active 过滤器缺失时触发重建。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        // 延迟 1 秒执行，避开启动阶段的其他初始化
        scheduler.schedule(() -> {
            try {
                bloomFilterService.rebuildIfMissing();
            } catch (Exception e) {
                // 重建失败只记录日志，布隆过滤器保持 fail-open
                log.warn("startup bloom rebuild failed, fail-open: {}", e.getMessage());
            }
        }, Instant.now().plusSeconds(1));
    }
}
