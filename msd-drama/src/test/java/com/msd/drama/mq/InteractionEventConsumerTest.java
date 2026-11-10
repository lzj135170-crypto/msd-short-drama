package com.msd.drama.mq;

import com.msd.common.event.InteractionEvent;
import com.msd.common.event.InteractionEventType;
import com.msd.common.event.KafkaTopics;
import com.msd.drama.cache.CacheEvictor;
import com.msd.drama.mapper.DramaCounterMapper;
import com.msd.drama.mapper.EventConsumeLogMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InteractionEventConsumerTest {

    private EventConsumeLogMapper consumeLogMapper;
    private DramaCounterMapper counterMapper;
    private CacheEvictor cacheEvictor;
    private InteractionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        consumeLogMapper = mock(EventConsumeLogMapper.class);
        counterMapper = mock(DramaCounterMapper.class);
        cacheEvictor = mock(CacheEvictor.class);
        consumer = new InteractionEventConsumer(jsonMapper, consumeLogMapper, counterMapper,
                cacheEvictor, mock(EpisodeDramaResolver.class));
    }

    @Test
    void dramaLikeEvictsHomeAndDetailAfterCommit() throws Exception {
        when(consumeLogMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);
        when(counterMapper.incrementDramaLike(5005L)).thenReturn(1);

        consumer.onMessage(record(new InteractionEvent(
                "event-1", InteractionEventType.LIKE, 100L,
                1, 5005L, 5005L, null, null, null, null, null, 1L)));

        verify(cacheEvictor).evictDetailDebounced(5005L);
        verify(cacheEvictor).evictHomeDebounced();
    }

    @Test
    void episodeLikeEvictsDetailButNotHome() throws Exception {
        when(consumeLogMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);
        when(counterMapper.incrementEpisodeLike(100L)).thenReturn(1);

        consumer.onMessage(record(new InteractionEvent(
                "event-2", InteractionEventType.LIKE, 100L,
                2, 100L, 5005L, 100L, null, null, null, null, 2L)));

        verify(cacheEvictor).evictDetailDebounced(5005L);
        verify(cacheEvictor, never()).evictHomeDebounced();
    }

    private ConsumerRecord<String, String> record(InteractionEvent event) {
        try {
            String value = JsonMapper.builder().build().writeValueAsString(event);
            return new ConsumerRecord<>(KafkaTopics.INTERACTION_LIKE, 0, 0, "key", value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
