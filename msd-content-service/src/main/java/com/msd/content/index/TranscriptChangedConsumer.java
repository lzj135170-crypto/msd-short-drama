package com.msd.content.index;

import com.msd.common.event.KafkaGroups;
import com.msd.common.event.KafkaTopics;
import com.msd.common.event.TranscriptChangedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 分集字幕变更消费者。
 *
 * <p>同步重建单集 transcript 索引；失败抛出交给 Kafka 重试。处理成功后才写幂等键，
 * 避免失败事件被误判为已处理。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TranscriptChangedConsumer {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(TranscriptChangedConsumer.class);

    // JSON 反序列化
    private final JsonMapper jsonMapper;
    // eventId 幂等
    private final IndexEventDeduplicator deduplicator;
    // 字幕索引服务
    private final TranscriptIndexService indexService;

    /**
     * 注入反序列化器、幂等器和索引服务。
     */
    public TranscriptChangedConsumer(JsonMapper jsonMapper,
                                     IndexEventDeduplicator deduplicator,
                                     TranscriptIndexService indexService) {
        this.jsonMapper = jsonMapper;
        this.deduplicator = deduplicator;
        this.indexService = indexService;
    }

    /**
     * 消费一条字幕变更事件。
     */
    @KafkaListener(
            topics = KafkaTopics.TRANSCRIPT_CHANGED,
            groupId = KafkaGroups.CONTENT_TRANSCRIPT_INDEX,
            concurrency = "${msd.ai.rag.incremental.concurrency:2}",
            autoStartup = "${msd.ai.rag.incremental.enabled:true}")
    public void onMessage(ConsumerRecord<String, String> record) {
        // 1. 反序列化并校验
        TranscriptChangedEvent event = jsonMapper.readValue(
                record.value(), TranscriptChangedEvent.class);
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("字幕变更事件缺少 eventId");
        }
        if (event.episodeId() == null || event.episodeId() <= 0) {
            throw new IllegalArgumentException("字幕变更事件缺少 episodeId");
        }

        // 2. 已处理事件直接跳过
        if (deduplicator.isProcessed(event.eventId())) {
            log.debug("duplicate transcript changed event ignored: eventId={}", event.eventId());
            return;
        }

        // 3. 同步索引；异常向上抛出，由 Kafka 容器重试
        indexService.apply(event);
        deduplicator.markIfNew(event.eventId());
        log.info("transcript changed event consumed: eventId={}, episodeId={}, action={}",
                event.eventId(), event.episodeId(), event.action());
    }
}
