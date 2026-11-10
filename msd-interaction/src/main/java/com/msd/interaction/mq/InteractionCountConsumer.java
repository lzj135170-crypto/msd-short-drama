package com.msd.interaction.mq;

import com.msd.common.event.InteractionEvent;
import com.msd.common.event.KafkaGroups;
import com.msd.common.event.KafkaTopics;
import com.msd.interaction.mapper.CommentMapper;
import com.msd.interaction.mapper.EventConsumeLogMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * interaction 评论计数消费者：点赞数、回复数由事件异步派生。
 *
 * <p>使用独立消费组从 latest 开始，避免新消费者回放历史 LIKE/COMMENT 造成重复计数。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.interaction.async-counter", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InteractionCountConsumer {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(InteractionCountConsumer.class);
    /** 目标类型：评论。 */
    private static final int TARGET_COMMENT = 3;

    // JSON 序列化器
    private final JsonMapper jsonMapper;
    // 消费幂等日志
    private final EventConsumeLogMapper consumeLogMapper;
    // 评论计数 Mapper
    private final CommentMapper commentMapper;

    /**
     * 注入 JSON、消费日志和评论 Mapper。
     */
    public InteractionCountConsumer(JsonMapper jsonMapper,
                                    EventConsumeLogMapper consumeLogMapper,
                                    CommentMapper commentMapper) {
        this.jsonMapper = jsonMapper;
        this.consumeLogMapper = consumeLogMapper;
        this.commentMapper = commentMapper;
    }

    /**
     * 消费一条互动事件，只处理评论点赞和回复计数。
     */
    @KafkaListener(
            topics = {
                    KafkaTopics.INTERACTION_LIKE,
                    KafkaTopics.INTERACTION_COMMENT
            },
            groupId = KafkaGroups.INTERACTION_COUNTER,
            concurrency = "${msd.kafka.interaction-concurrency:6}",
            properties = {"auto.offset.reset=latest"})
    @Transactional
    public void onMessage(ConsumerRecord<String, String> record) {
        // 1. 反序列化和基础校验；非法消息进入重试/DLQ
        InteractionEvent event = jsonMapper.readValue(record.value(), InteractionEvent.class);
        if (event.eventType() == null) {
            throw new IllegalArgumentException("事件缺少 eventType");
        }
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("事件缺少 eventId");
        }

        // 2. 只有评论点赞/取消点赞需要 interaction 侧计数
        if (event.eventType() == com.msd.common.event.InteractionEventType.LIKE
                || event.eventType() == com.msd.common.event.InteractionEventType.UNLIKE) {
            if (!Integer.valueOf(TARGET_COMMENT).equals(event.targetType())) {
                return;
            }
            applyCommentLike(event, record);
            return;
        }

        // 3. 只有回复创建/删除需要更新根评论 reply_count
        if (event.eventType() == com.msd.common.event.InteractionEventType.COMMENT
                || event.eventType() == com.msd.common.event.InteractionEventType.COMMENT_DELETED) {
            if (event.rootId() == null || event.rootId() == 0L) {
                return;
            }
            applyReplyCount(event, record);
        }
    }

    /**
     * 评论点赞/取消点赞：先写幂等日志，再更新评论计数。
     */
    private void applyCommentLike(InteractionEvent event, ConsumerRecord<String, String> record) {
        if (consumeLogMapper.insertIgnore(KafkaGroups.INTERACTION_COUNTER,
                event.eventId(), record.topic()) == 0) {
            log.debug("duplicate comment like ignored: eventId={}", event.eventId());
            return;
        }
        Long commentId = event.targetId();
        if (commentId == null) {
            throw new IllegalArgumentException("评论点赞事件缺少 targetId");
        }
        int affected = event.eventType() == com.msd.common.event.InteractionEventType.LIKE
                ? commentMapper.incrementLikeCount(commentId)
                : commentMapper.decrementLikeCount(commentId);
        if (affected == 0) {
            log.warn("comment like target not found or hidden: commentId={}, eventId={}",
                    commentId, event.eventId());
        }
    }

    /**
     * 回复创建/删除：更新根评论 reply_count。
     */
    private void applyReplyCount(InteractionEvent event, ConsumerRecord<String, String> record) {
        if (consumeLogMapper.insertIgnore(KafkaGroups.INTERACTION_COUNTER,
                event.eventId(), record.topic()) == 0) {
            log.debug("duplicate comment reply ignored: eventId={}", event.eventId());
            return;
        }
        int affected = event.eventType() == com.msd.common.event.InteractionEventType.COMMENT
                ? commentMapper.incrementReplyCount(event.rootId())
                : commentMapper.decrementReplyCount(event.rootId());
        if (affected == 0) {
            log.warn("comment root not found or hidden: rootId={}, eventId={}",
                    event.rootId(), event.eventId());
        }
        log.info("comment reply count applied: eventId={}, type={}, rootId={}",
                event.eventId(), event.eventType(), event.rootId());
    }
}
