package com.msd.interaction.mq;

import com.msd.common.event.InteractionEvent;
import com.msd.common.event.InteractionEventType;
import com.msd.common.event.KafkaTopics;
import com.msd.interaction.mapper.CommentMapper;
import com.msd.interaction.mapper.EventConsumeLogMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * interaction 侧评论计数消费者测试。
 */
class InteractionCountConsumerTest {

    private EventConsumeLogMapper consumeLogMapper;
    private CommentMapper commentMapper;
    private InteractionCountConsumer consumer;

    @BeforeEach
    void setUp() {
        consumeLogMapper = mock(EventConsumeLogMapper.class);
        commentMapper = mock(CommentMapper.class);
        consumer = new InteractionCountConsumer(JsonMapper.builder().build(),
                consumeLogMapper, commentMapper);
    }

    /**
     * 评论点赞只更新评论计数，不影响短剧计数。
     */
    @Test
    void appliesCommentLike() {
        when(consumeLogMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        consumer.onMessage(record(new InteractionEvent(
                "event-like", InteractionEventType.LIKE, 1003L,
                3, 8301L, null, null, 8301L, null, null, null, 1L)));

        verify(commentMapper).incrementLikeCount(8301L);
    }

    /**
     * 取消评论点赞扣减计数。
     */
    @Test
    void appliesCommentUnlike() {
        when(consumeLogMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        consumer.onMessage(record(new InteractionEvent(
                "event-unlike", InteractionEventType.UNLIKE, 1003L,
                3, 8301L, null, null, 8301L, null, null, null, 1L)));

        verify(commentMapper).decrementLikeCount(8301L);
    }

    /**
     * 回复创建让根评论 reply_count 加一。
     */
    @Test
    void appliesReplyComment() {
        when(consumeLogMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(1);

        consumer.onMessage(record(new InteractionEvent(
                "event-reply", InteractionEventType.COMMENT, 1003L,
                null, null, 5001L, 6001L, 8302L, 8301L, 8301L, null, 1L)));

        verify(commentMapper).incrementReplyCount(8301L);
    }

    /**
     * 重复事件必须跳过。
     */
    @Test
    void skipsDuplicateEvent() {
        when(consumeLogMapper.insertIgnore(anyString(), anyString(), anyString())).thenReturn(0);

        consumer.onMessage(record(new InteractionEvent(
                "event-like", InteractionEventType.LIKE, 1003L,
                3, 8301L, null, null, 8301L, null, null, null, 1L)));

        verify(commentMapper, never()).incrementLikeCount(8301L);
    }

    /**
     * 非法 JSON 必须抛出，交给 error handler 重试和 DLQ。
     */
    @Test
    void rejectsMalformedPayload() {
        assertThatThrownBy(() -> consumer.onMessage(new ConsumerRecord<>(
                KafkaTopics.INTERACTION_LIKE, 0, 0L, "key", "{\"broken\":")))
                .isInstanceOf(Exception.class);
    }

    /**
     * 构造 Kafka 记录。
     */
    private ConsumerRecord<String, String> record(InteractionEvent event) {
        try {
            return new ConsumerRecord<>(KafkaTopics.INTERACTION_LIKE, 0, 0L, "key",
                    JsonMapper.builder().build().writeValueAsString(event));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
