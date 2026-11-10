package com.msd.content.index;

import com.msd.common.event.DramaChangedEvent;
import com.msd.common.event.KafkaGroups;
import com.msd.common.event.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 短剧索引变更消费者。
 *
 * <p>解析并校验事件，按 eventId 幂等，再交给按 dramaId 去抖的调度器。
 * 非法消息直接抛出，由 Kafka 容器重试后丢弃并记录错误。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DramaChangedConsumer {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DramaChangedConsumer.class);

    // JSON 反序列化
    private final JsonMapper jsonMapper;
    // eventId 幂等
    private final IndexEventDeduplicator deduplicator;
    // 按短剧去抖的索引调度器
    private final DramaIndexUpdateScheduler scheduler;

    /**
     * 注入反序列化器、幂等器和调度器。
     */
    public DramaChangedConsumer(JsonMapper jsonMapper,
                                IndexEventDeduplicator deduplicator,
                                DramaIndexUpdateScheduler scheduler) {
        this.jsonMapper = jsonMapper;
        this.deduplicator = deduplicator;
        this.scheduler = scheduler;
    }

    /**
     * 消费一条短剧变更事件。
     */
    @KafkaListener(
            topics = KafkaTopics.DRAMA_CHANGED,
            groupId = KafkaGroups.CONTENT_RAG_INDEX,
            concurrency = "${msd.ai.rag.incremental.concurrency:2}",
            autoStartup = "${msd.ai.rag.incremental.enabled:true}")
    public void onMessage(ConsumerRecord<String, String> record) {
        // 1. 反序列化并校验必要字段
        DramaChangedEvent event = jsonMapper.readValue(
                record.value(), DramaChangedEvent.class);
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("短剧变更事件缺少 eventId");
        }
        if (event.dramaId() == null || event.dramaId() <= 0) {
            throw new IllegalArgumentException("短剧变更事件缺少 dramaId");
        }

        // 2. 重复事件直接跳过
        if (!deduplicator.markIfNew(event.eventId())) {
            log.debug("duplicate drama changed event ignored: eventId={}", event.eventId());
            return;
        }

        // 3. 交给调度器去抖和重试
        scheduler.schedule(event);
        log.info("drama changed event accepted: eventId={}, dramaId={}, action={}, "
                        + "topic={}, partition={}, offset={}",
                event.eventId(), event.dramaId(), event.action(), record.topic(),
                record.partition(), record.offset());
    }
}
