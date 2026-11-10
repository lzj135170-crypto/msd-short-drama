package com.msd.drama.mq;

import com.msd.common.event.InteractionEvent;
import com.msd.common.event.InteractionEventType;
import com.msd.common.event.KafkaTopics;
import com.msd.drama.cache.CacheEvictor;
import com.msd.drama.mapper.DramaCounterMapper;
import com.msd.drama.mapper.EventConsumeLogMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * drama 批量消费聚合测试。
 */
class InteractionEventBatchConsumerTest {

    /**
     * 同一部剧的多条点赞聚合成一次批量 UPDATE，缓存只失效一次。
     */
    @Test
    void aggregatesDramaLikeDeltas() {
        EventConsumeLogMapper consumeLogMapper = mock(EventConsumeLogMapper.class);
        DramaCounterMapper counterMapper = mock(DramaCounterMapper.class);
        CacheEvictor cacheEvictor = mock(CacheEvictor.class);
        when(consumeLogMapper.selectExistingEventIds(anyString(), anyList())).thenReturn(List.of());
        when(consumeLogMapper.insertIgnoreBatch(anyList())).thenReturn(2);
        InteractionEventBatchConsumer consumer = new InteractionEventBatchConsumer(
                JsonMapper.builder().build(), consumeLogMapper, counterMapper,
                cacheEvictor, mock(EpisodeDramaResolver.class));

        consumer.onBatch(List.of(
                record("event-1", InteractionEventType.LIKE, 5001L),
                record("event-2", InteractionEventType.LIKE, 5001L)));

        verify(counterMapper).batchDramaLike(argThat((Map<Long, Long> deltas) ->
                Long.valueOf(2L).equals(deltas.get(5001L))));
        verify(cacheEvictor).evictDetailDebounced(5001L);
        verify(cacheEvictor).evictHomeDebounced();
    }

    /**
     * 已消费事件不参与聚合。
     */
    @Test
    void skipsAlreadyConsumedEvent() {
        EventConsumeLogMapper consumeLogMapper = mock(EventConsumeLogMapper.class);
        DramaCounterMapper counterMapper = mock(DramaCounterMapper.class);
        when(consumeLogMapper.selectExistingEventIds(anyString(), anyList()))
                .thenReturn(List.of("event-1"));
        InteractionEventBatchConsumer consumer = new InteractionEventBatchConsumer(
                JsonMapper.builder().build(), consumeLogMapper, counterMapper,
                mock(CacheEvictor.class), mock(EpisodeDramaResolver.class));

        consumer.onBatch(List.of(record("event-1", InteractionEventType.LIKE, 5001L)));

        verify(counterMapper, org.mockito.Mockito.never()).batchDramaLike(org.mockito.ArgumentMatchers.anyMap());
    }

    /**
     * 构造短剧点赞记录。
     */
    private ConsumerRecord<String, String> record(String eventId, InteractionEventType type,
            Long dramaId) {
        try {
            InteractionEvent event = new InteractionEvent(
                    eventId, type, 1003L, 1, dramaId, dramaId,
                    null, null, null, null, null, 1L);
            return new ConsumerRecord<>(KafkaTopics.INTERACTION_LIKE, 0, 0L, "drama:" + dramaId,
                    JsonMapper.builder().build().writeValueAsString(event));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
