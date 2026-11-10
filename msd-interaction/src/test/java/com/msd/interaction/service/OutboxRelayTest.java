package com.msd.interaction.service;

import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 认领、并行投递与条件更新的单元测试。
 */
class OutboxRelayTest {

    private OutboxEventMapper outboxEventMapper;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxClaimService claimService;
    private ExecutorService executor;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        outboxEventMapper = mock(OutboxEventMapper.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        claimService = mock(OutboxClaimService.class);
        executor = Executors.newFixedThreadPool(2);
        when(outboxEventMapper.countPending()).thenReturn(1L);
        relay = new OutboxRelay(claimService, outboxEventMapper, kafkaTemplate,
                executor, new SimpleMeterRegistry(), 200, 5, 1000);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /**
     * 发送成功后必须按 claim token 标记 sent，并清理认领。
     */
    @Test
    void marksSentWithClaimToken() {
        OutboxEvent event = event(1L, "event-1");
        when(claimService.claim(anyInt(), anyString(), anyInt())).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relay();

        verify(outboxEventMapper).markSent(anyLong(), anyString());
        verify(outboxEventMapper, never()).markRetry(anyLong(), anyString(), anyInt(), any(), anyString());
    }

    /**
     * 发送失败时按条件更新重试，且不会误标记 sent。
     */
    @Test
    void schedulesRetryOnSendFailure() {
        OutboxEvent event = event(2L, "event-2");
        when(claimService.claim(anyInt(), anyString(), anyInt())).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        relay.relay();

        verify(outboxEventMapper).markRetry(anyLong(), anyString(), anyInt(), any(), anyString());
        verify(outboxEventMapper, never()).markSent(anyLong(), anyString());
    }

    /**
     * 空批次不调用发送。
     */
    @Test
    void emptyClaimDoesNothing() {
        when(claimService.claim(anyInt(), anyString(), anyInt())).thenReturn(List.of());

        relay.relay();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    /**
     * 构造待发送事件。
     */
    private OutboxEvent event(Long id, String eventId) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setEventId(eventId);
        event.setTopic("msd.interaction.like.v1");
        event.setEventKey("drama:5001");
        event.setPayload("{}");
        event.setRetryCount(0);
        event.setUserId(1003L);
        return event;
    }
}
