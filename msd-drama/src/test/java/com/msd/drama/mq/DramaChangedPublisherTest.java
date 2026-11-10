package com.msd.drama.mq;

import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.KafkaTopics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 短剧索引事件发布器的事务提交、消息内容和失败降级测试。
 */
class DramaChangedPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final DramaChangedPublisher publisher =
            new DramaChangedPublisher(kafkaTemplate, JsonMapper.shared(), true);

    /**
     * 清理测试事务同步状态。
     */
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 无事务时立即发送，消息携带 dramaId 和动作。
     */
    @Test
    void publishesImmediatelyWithoutTransaction() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishAfterCommit(5003L, DramaChangedAction.UPSERT);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq(KafkaTopics.DRAMA_CHANGED),
                org.mockito.ArgumentMatchers.eq("5003"), payload.capture());
        assertThat(payload.getValue())
                .contains("\"dramaId\":5003")
                .contains("\"action\":\"UPSERT\"");
    }

    /**
     * 事务内延迟到提交后发送。
     */
    @Test
    void publishesOnlyAfterCommit() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishAfterCommit(5003L, DramaChangedAction.DELETE);
            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(kafkaTemplate).send(
                    org.mockito.ArgumentMatchers.eq(KafkaTopics.DRAMA_CHANGED),
                    org.mockito.ArgumentMatchers.eq("5003"), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * Kafka 发送异常不能影响审核或下架结果。
     */
    @Test
    void swallowsSendFailure() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("kafka down"));

        assertThatCode(() -> publisher.publishAfterCommit(5003L, DramaChangedAction.UPSERT))
                .doesNotThrowAnyException();
    }

    /**
     * 开关关闭时不发送。
     */
    @Test
    void skipsWhenDisabled() {
        DramaChangedPublisher disabled =
                new DramaChangedPublisher(kafkaTemplate, JsonMapper.shared(), false);

        disabled.publishAfterCommit(5003L, DramaChangedAction.UPSERT);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }
}
