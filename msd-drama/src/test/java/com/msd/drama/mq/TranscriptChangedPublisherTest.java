package com.msd.drama.mq;

import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.KafkaTopics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字幕变更事件发布器测试。
 */
class TranscriptChangedPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final TranscriptChangedPublisher publisher =
            new TranscriptChangedPublisher(kafkaTemplate, JsonMapper.shared(), true);

    /**
     * 发送带 episodeId 和动作的事件。
     */
    @Test
    void publishesTranscriptEvent() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishAfterCommit(6005L, DramaChangedAction.UPSERT);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.TRANSCRIPT_CHANGED), eq("6005"),
                payload.capture());
        assertThat(payload.getValue())
                .contains("\"episodeId\":6005")
                .contains("\"action\":\"UPSERT\"");
    }

    /**
     * Kafka 异常不影响字幕入库。
     */
    @Test
    void swallowsSendFailure() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("kafka down"));

        assertThatCode(() -> publisher.publishAfterCommit(6005L, DramaChangedAction.DELETE))
                .doesNotThrowAnyException();
    }
}
