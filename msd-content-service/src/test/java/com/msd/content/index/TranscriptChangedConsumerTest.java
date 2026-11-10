package com.msd.content.index;

import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.TranscriptChangedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字幕消费者字段校验、幂等和成功标记测试。
 */
class TranscriptChangedConsumerTest {

    private final JsonMapper jsonMapper = JsonMapper.shared();
    private final IndexEventDeduplicator deduplicator = mock(IndexEventDeduplicator.class);
    private final TranscriptIndexService indexService = mock(TranscriptIndexService.class);
    private final TranscriptChangedConsumer consumer =
            new TranscriptChangedConsumer(jsonMapper, deduplicator, indexService);

    /**
     * 新事件同步索引，成功后写幂等键。
     */
    @Test
    void indexesNewEventAndMarksProcessed() throws Exception {
        TranscriptChangedEvent event = new TranscriptChangedEvent(
                "event-1", 6005L, DramaChangedAction.UPSERT, 1000L);
        when(deduplicator.isProcessed("event-1")).thenReturn(false);

        consumer.onMessage(record(jsonMapper.writeValueAsString(event)));

        verify(indexService).apply(event);
        verify(deduplicator).markIfNew("event-1");
    }

    /**
     * 已处理事件不再索引。
     */
    @Test
    void skipsProcessedEvent() throws Exception {
        TranscriptChangedEvent event = new TranscriptChangedEvent(
                "event-1", 6005L, DramaChangedAction.DELETE, 1000L);
        when(deduplicator.isProcessed("event-1")).thenReturn(true);

        consumer.onMessage(record(jsonMapper.writeValueAsString(event)));

        verify(indexService, never()).apply(any());
        verify(deduplicator, never()).markIfNew(anyString());
    }

    /**
     * 缺少 episodeId 的消息抛出，由 Kafka 重试。
     */
    @Test
    void rejectsEventWithoutEpisodeId() throws Exception {
        TranscriptChangedEvent event = new TranscriptChangedEvent(
                "event-1", null, DramaChangedAction.UPSERT, 1000L);

        assertThatThrownBy(() -> consumer.onMessage(
                record(jsonMapper.writeValueAsString(event))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("episodeId");
    }

    /**
     * 构造 Kafka 记录。
     */
    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("msd.transcript.changed.v1", 0, 0L, "6005", value);
    }
}
