package com.msd.drama.mq;

import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.KafkaTopics;
import com.msd.common.event.TranscriptChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * 分集字幕变更事件发布器。
 *
 * <p>与短剧索引事件一致，事务提交后发送；发送失败只记日志，由全量/重放兜底。</p>
 */
@Component
public class TranscriptChangedPublisher {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(TranscriptChangedPublisher.class);

    // Kafka 生产者
    private final KafkaTemplate<String, String> kafkaTemplate;
    // JSON 序列化器
    private final JsonMapper jsonMapper;
    // 是否启用字幕增量索引
    private final boolean enabled;

    /**
     * 注入 Kafka 生产者、序列化器和开关。
     */
    public TranscriptChangedPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                      JsonMapper jsonMapper,
                                      @Value("${msd.kafka.transcript-changed.enabled:true}")
                                      boolean enabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
    }

    /**
     * 事务提交后发布字幕变更。
     */
    public void publishAfterCommit(Long episodeId, DramaChangedAction action) {
        // 1. 关闭或参数非法时跳过
        if (!enabled || episodeId == null || episodeId <= 0 || action == null) {
            return;
        }
        // 2. 事务内延迟到提交后发送，回滚时不产生索引变更
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish(episodeId, action);
                        }
                    });
            return;
        }
        publish(episodeId, action);
    }

    /**
     * 发送事件；Kafka 不可用时只记录日志。
     */
    void publish(Long episodeId, DramaChangedAction action) {
        try {
            TranscriptChangedEvent event = new TranscriptChangedEvent(
                    UUID.randomUUID().toString(), episodeId, action, System.currentTimeMillis());
            String payload = jsonMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.TRANSCRIPT_CHANGED,
                            String.valueOf(episodeId), payload)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("transcript changed publish failed: episodeId={}, "
                                    + "action={}, error={}", episodeId, action,
                                    rootMessage(error));
                        }
                    });
            log.info("transcript changed published: episodeId={}, action={}, eventId={}",
                    episodeId, action, event.eventId());
        } catch (Exception e) {
            log.warn("transcript changed publish skipped: episodeId={}, action={}, error={}",
                    episodeId, action, rootMessage(e));
        }
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
