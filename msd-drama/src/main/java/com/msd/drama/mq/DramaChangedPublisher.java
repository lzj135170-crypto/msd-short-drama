package com.msd.drama.mq;

import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.DramaChangedEvent;
import com.msd.common.event.KafkaTopics;
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
 * 短剧索引变更事件发布器。
 *
 * <p>索引允许最终一致，不引入 outbox；在事务提交后发送，发送失败只记录日志，
 * 不影响审核或下架结果，索引由全量重建兜底。</p>
 */
@Component
public class DramaChangedPublisher {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DramaChangedPublisher.class);

    // Kafka 生产者
    private final KafkaTemplate<String, String> kafkaTemplate;
    // JSON 序列化器
    private final JsonMapper jsonMapper;
    // 是否启用增量索引事件
    private final boolean enabled;

    /**
     * 注入 Kafka 生产者、序列化器和开关。
     */
    public DramaChangedPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                 JsonMapper jsonMapper,
                                 @Value("${msd.kafka.drama-changed.enabled:true}") boolean enabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
    }

    /**
     * 在事务提交后发布事件；没有事务时立即发布。
     *
     * @param dramaId 短剧 ID
     * @param action  上架/内容变更或下架
     */
    public void publishAfterCommit(Long dramaId, DramaChangedAction action) {
        // 1. 关闭或参数非法时跳过
        if (!enabled || dramaId == null || dramaId <= 0 || action == null) {
            return;
        }
        // 2. 事务内延迟到提交后发送，回滚时不产生索引变更
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish(dramaId, action);
                        }
                    });
            return;
        }
        publish(dramaId, action);
    }

    /**
     * 发送事件；Kafka 不可用时只记录日志，绝不抛出。
     */
    void publish(Long dramaId, DramaChangedAction action) {
        try {
            DramaChangedEvent event = new DramaChangedEvent(
                    UUID.randomUUID().toString(), dramaId, action, System.currentTimeMillis());
            String payload = jsonMapper.writeValueAsString(event);
            kafkaTemplate.send(KafkaTopics.DRAMA_CHANGED, String.valueOf(dramaId), payload)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("drama changed publish failed: dramaId={}, action={}, error={}",
                                    dramaId, action, rootMessage(error));
                        }
                    });
            log.info("drama changed published: dramaId={}, action={}, eventId={}",
                    dramaId, action, event.eventId());
        } catch (Exception e) {
            log.warn("drama changed publish skipped: dramaId={}, action={}, error={}",
                    dramaId, action, rootMessage(e));
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
