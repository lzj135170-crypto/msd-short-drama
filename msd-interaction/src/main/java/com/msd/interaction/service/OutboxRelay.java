package com.msd.interaction.service;

import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 中继：短事务认领、事务外并行投递、按 claim token 条件回写。
 *
 * <p>at-least-once 语义，消费端必须按 eventId 幂等。多实例通过 claim_token
 * 和 locked_until 租约避免重复发送；实例崩溃后租约到期可被重新认领。</p>
 */
@Service
public class OutboxRelay {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    /** 认领租约秒数。 */
    private static final int LEASE_SECONDS = 60;
    /** last_error 列长度。 */
    private static final int MAX_ERROR_LENGTH = 500;
    /** 指数退避上限，单位秒。 */
    private static final int MAX_BACKOFF_SECONDS = 60;

    // 短事务认领服务
    private final OutboxClaimService claimService;
    // outbox 事件 Mapper
    private final OutboxEventMapper outboxEventMapper;
    // Kafka 发送客户端
    private final KafkaTemplate<String, String> kafkaTemplate;
    // 并行发送线程池
    private final ExecutorService sendExecutor;
    // 指标注册表
    private final MeterRegistry meterRegistry;
    // 每批扫描的待发送事件数
    private final int batchSize;
    // 最大重试次数，达到后转死信
    private final int maxRetry;
    // 单条 Kafka 发送等待上限（毫秒）
    private final long sendTimeoutMs;
    // 积压指标
    private final AtomicLong pendingGauge = new AtomicLong();
    // 最老事件年龄指标（秒）
    private final AtomicLong oldestAgeGauge = new AtomicLong();

    /**
     * 注入 Mapper、Kafka 客户端、发送线程池和 outbox 参数。
     */
    public OutboxRelay(OutboxClaimService claimService,
                       OutboxEventMapper outboxEventMapper,
                       KafkaTemplate<String, String> kafkaTemplate,
                       @Qualifier("outboxSendExecutor") ExecutorService sendExecutor,
                       MeterRegistry meterRegistry,
                       @Value("${msd.outbox.batch-size:200}") int batchSize,
                       @Value("${msd.outbox.max-retry:5}") int maxRetry,
                       @Value("${msd.outbox.send-timeout-ms:1000}") long sendTimeoutMs) {
        this.claimService = claimService;
        this.outboxEventMapper = outboxEventMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.sendExecutor = sendExecutor;
        this.meterRegistry = meterRegistry;
        this.batchSize = Math.max(batchSize, 1);
        this.maxRetry = Math.max(maxRetry, 1);
        this.sendTimeoutMs = Math.max(sendTimeoutMs, 100L);
        Gauge.builder("msd.outbox.pending", pendingGauge, AtomicLong::get)
                .description("pending outbox events")
                .register(meterRegistry);
        Gauge.builder("msd.outbox.oldest.age.seconds", oldestAgeGauge, AtomicLong::get)
                .description("age of oldest pending outbox event")
                .register(meterRegistry);
    }

    /**
     * 定时认领并并行投递一批事件。
     */
    @Scheduled(fixedDelayString = "${msd.outbox.relay-interval-ms:1000}")
    public void relay() {
        // 1. 短事务认领；发送必须在事务外进行
        String token = UUID.randomUUID().toString();
        List<OutboxEvent> events;
        try {
            events = claimService.claim(batchSize, token, LEASE_SECONDS);
        } catch (Exception ex) {
            log.warn("outbox claim failed", ex);
            refreshBacklogMetrics();
            return;
        }
        if (events.isEmpty()) {
            refreshBacklogMetrics();
            return;
        }

        // 2. 并行发送，每个任务自行回写成功/失败，互不影响
        List<Callable<Void>> tasks = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            tasks.add(() -> {
                sendOne(event, token);
                return null;
            });
        }
        try {
            List<Future<Void>> futures = sendExecutor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("outbox relay interrupted");
        } catch (Exception ex) {
            log.warn("outbox relay batch failed", ex);
        } finally {
            refreshBacklogMetrics();
        }
    }

    /**
     * 发送单条事件；失败按指数退避重试或转死信。
     */
    private void sendOne(OutboxEvent event, String token) {
        long start = System.nanoTime();
        try {
            kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            int updated = outboxEventMapper.markSent(event.getId(), token);
            if (updated == 0) {
                log.warn("outbox sent but claim lost: id={}, eventId={}",
                        event.getId(), event.getEventId());
            }
            recordSend("success", start);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordSend("interrupted", start);
            handleFailure(event, token, ex);
        } catch (Exception ex) {
            recordSend("failure", start);
            handleFailure(event, token, ex);
        }
    }

    /**
     * 累计发送指标。
     */
    private void recordSend(String outcome, long startNanos) {
        meterRegistry.counter("msd.outbox.send", "outcome", outcome).increment();
        meterRegistry.timer("msd.outbox.send.latency", "outcome", outcome)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /**
     * 处理单条发送失败：未达上限按指数退避，达到上限转死信。
     */
    private void handleFailure(OutboxEvent event, String token, Exception ex) {
        // 1. 递增重试次数并截断错误信息
        int retryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        String error = truncate(String.valueOf(ex));

        // 2. 达到最大重试次数，标记死信
        if (retryCount >= maxRetry) {
            outboxEventMapper.markDead(event.getId(), token, retryCount, error);
            meterRegistry.counter("msd.outbox.dead").increment();
            log.error("outbox dead: id={}, eventId={}, topic={}, retry={}, error={}",
                    event.getId(), event.getEventId(), event.getTopic(), retryCount, error);
            return;
        }

        // 3. 指数退避 2^retry 秒，上限 60 秒
        long backoffSeconds = Math.min(1L << Math.min(retryCount, 6), MAX_BACKOFF_SECONDS);
        LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(backoffSeconds);
        outboxEventMapper.markRetry(event.getId(), token, retryCount, nextRetryTime, error);
        meterRegistry.counter("msd.outbox.retry").increment();
        log.warn("outbox retry scheduled: id={}, eventId={}, topic={}, retry={}, backoff={}s, error={}",
                event.getId(), event.getEventId(), event.getTopic(), retryCount, backoffSeconds, error);
    }

    /**
     * 刷新积压数和最老事件年龄。
     */
    private void refreshBacklogMetrics() {
        try {
            pendingGauge.set(outboxEventMapper.countPending());
            LocalDateTime oldest = outboxEventMapper.selectOldestPendingCreateTime();
            oldestAgeGauge.set(oldest == null ? 0L
                    : Math.max(0L, Duration.between(oldest, LocalDateTime.now()).toSeconds()));
        } catch (Exception ex) {
            log.debug("refresh outbox backlog metrics failed", ex);
        }
    }

    /**
     * 截断错误信息，保证不超过 last_error 列长度。
     */
    private String truncate(String error) {
        if (error == null) {
            return "";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
