package com.msd.interaction.service;

import com.msd.common.exception.BizException;
import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.mapper.EventConsumeLogMapper;
import com.msd.interaction.mapper.OutboxEventMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事件状态查询与归属校验测试。
 */
class EventStatusServiceTest {

    /**
     * 事件归属用户可以看到发送状态和已消费组。
     */
    @Test
    void returnsStatusForOwner() {
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        EventConsumeLogMapper consumeLogMapper = mock(EventConsumeLogMapper.class);
        OutboxEvent event = new OutboxEvent();
        event.setEventId("event-1");
        event.setUserId(1003L);
        event.setStatus(1);
        event.setEventType("LIKE");
        event.setRetryCount(0);
        event.setSentAt(LocalDateTime.now());
        event.setLastError("");
        when(outboxMapper.selectByEventId("event-1")).thenReturn(event);
        when(consumeLogMapper.selectGroups("event-1"))
                .thenReturn(List.of("msd-drama-counter-v1"));
        EventStatusService service = new EventStatusService(outboxMapper, consumeLogMapper);

        EventStatusService.EventStatus status = service.get(1003L, "event-1");

        assertThat(status.status()).isEqualTo(1);
        assertThat(status.statusName()).isEqualTo("SENT");
        assertThat(status.consumedGroups()).containsExactly("msd-drama-counter-v1");
    }

    /**
     * 其他用户查询同一事件必须返回 404，不能泄露事件存在性。
     */
    @Test
    void rejectsOtherUser() {
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        OutboxEvent event = new OutboxEvent();
        event.setEventId("event-1");
        event.setUserId(1003L);
        when(outboxMapper.selectByEventId("event-1")).thenReturn(event);
        EventStatusService service = new EventStatusService(outboxMapper,
                mock(EventConsumeLogMapper.class));

        assertThatThrownBy(() -> service.get(1006L, "event-1"))
                .isInstanceOf(BizException.class);
    }

    /**
     * 不存在的事件同样返回 404。
     */
    @Test
    void rejectsMissingEvent() {
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        EventStatusService service = new EventStatusService(outboxMapper,
                mock(EventConsumeLogMapper.class));

        assertThatThrownBy(() -> service.get(1003L, "missing"))
                .isInstanceOf(BizException.class);
    }
}
