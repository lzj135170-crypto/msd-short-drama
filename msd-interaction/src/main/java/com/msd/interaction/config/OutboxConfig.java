package com.msd.interaction.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbox 并行发送线程池。
 */
@Configuration
public class OutboxConfig {

    /**
     * 固定 8 个发送线程，避免 Kafka 慢时无限创建线程。
     */
    @Bean(name = "outboxSendExecutor", destroyMethod = "shutdownNow")
    public ExecutorService outboxSendExecutor() {
        AtomicInteger index = new AtomicInteger();
        return Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "outbox-send-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
