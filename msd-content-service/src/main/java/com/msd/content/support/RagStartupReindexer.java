package com.msd.content.support;

import com.msd.content.config.RagProperties;
import com.msd.content.index.DramaIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 启动后异步重建索引。
 *
 * <p>只在 msd.ai.rag.reindex-on-startup=true 时触发，使用统一的 ragIndexExecutor，
 * 失败不阻塞服务启动。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RagStartupReindexer implements ApplicationListener<ApplicationReadyEvent> {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(RagStartupReindexer.class);

    // RAG 配置
    private final RagProperties properties;
    // 索引重建服务
    private final DramaIndexService indexService;
    // 启动重建使用的索引线程池
    private final Executor indexExecutor;

    /**
     * 注入配置、索引服务和统一的索引线程池。
     */
    public RagStartupReindexer(RagProperties properties,
                               DramaIndexService indexService,
                               @Qualifier("ragIndexExecutor") Executor indexExecutor) {
        this.properties = properties;
        this.indexService = indexService;
        this.indexExecutor = indexExecutor;
    }

    /**
     * 应用就绪后按配置提交重建任务。
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 1. 未开启启动重建时直接返回
        if (!properties.isReindexOnStartup()) {
            return;
        }
        // 2. 异步提交重建，失败只记录日志，不阻塞启动
        indexExecutor.execute(() -> {
            try {
                indexService.reindex(false);
            } catch (Exception e) {
                log.warn("rag startup reindex failed: {}", e.getMessage());
            }
        });
    }
}
