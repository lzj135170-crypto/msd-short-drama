package com.msd.content.index;

import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.DramaChangedEvent;
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
 * DRAMA_CHANGED 消费者的字段校验和幂等测试。
 */
class DramaChangedConsumerTest {

    private final JsonMapper jsonMapper = JsonMapper.shared();
    private final IndexEventDeduplicator deduplicator = mock(IndexEventDeduplicator.class);
    private final DramaIndexUpdateScheduler scheduler = mock(DramaIndexUpdateScheduler.class);
    private final DramaChangedConsumer consumer =
            new DramaChangedConsumer(jsonMapper, deduplicator, scheduler);

    /**
     * 合法事件去重后交给调度器。
     */
    @Test
    void acceptsValidEvent() throws Exception {
        DramaChangedEvent event = new DramaChangedEvent(
                "event-1", 5005L, DramaChangedAction.UPSERT, 1000L);
        when(deduplicator.markIfNew("event-1")).thenReturn(true);

        consumer.onMessage(record(jsonMapper.writeValueAsString(event)));

        verify(scheduler).schedule(event);
    }

    /**
     * 重复事件不再调度。
     */
    @Test
    void ignoresDuplicateEvent() throws Exception {
        DramaChangedEvent event = new DramaChangedEvent(
                "event-1", 5005L, DramaChangedAction.DELETE, 1000L);
        when(deduplicator.markIfNew("event-1")).thenReturn(false);

        consumer.onMessage(record(jsonMapper.writeValueAsString(event)));

        verify(scheduler, never()).schedule(any());
    }

    /**
     * 缺少 eventId 的非法消息抛出，交给容器重试。
     */
    @Test
    void rejectsEventWithoutEventId() throws Exception {
        DramaChangedEvent event = new DramaChangedEvent(
                "", 5005L, DramaChangedAction.UPSERT, 1000L);

        assertThatThrownBy(() -> consumer.onMessage(
                record(jsonMapper.writeValueAsString(event))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
        verify(deduplicator, never()).markIfNew(anyString());
    }

    /**
     * 构造 Kafka 记录。
     */
    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("msd.drama.changed.v1", 0, 0L, "5005", value);
    }
}
