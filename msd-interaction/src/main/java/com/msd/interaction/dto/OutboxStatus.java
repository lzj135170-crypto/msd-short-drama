package com.msd.interaction.dto;

import com.msd.interaction.entity.OutboxEvent;

import java.time.LocalDateTime;

/**
 * Outbox 事件状态，供测试台观察投递过程。
 *
 * @param id            事件记录 ID
 * @param eventId       事件 UUID，消费端幂等键
 * @param topic         Kafka Topic
 * @param eventType     事件类型
 * @param aggregateType 聚合类型
 * @param aggregateId   聚合根 ID
 * @param status        状态：0 待发送，1 已发送，2 死信
 * @param retryCount    重试次数
 * @param nextRetryTime 下次重试时间
 * @param sentAt        发送成功时间
 * @param lastError     最后一次错误
 * @param createTime    创建时间
 */
public record OutboxStatus(
        Long id,
        String eventId,
        String topic,
        String eventType,
        String aggregateType,
        Long aggregateId,
        Integer status,
        Integer retryCount,
        LocalDateTime nextRetryTime,
        LocalDateTime sentAt,
        String lastError,
        LocalDateTime createTime
) {

    /**
     * 实体转状态 DTO。
     */
    public static OutboxStatus from(OutboxEvent event) {
        return new OutboxStatus(
                event.getId(),
                event.getEventId(),
                event.getTopic(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getStatus(),
                event.getRetryCount(),
                event.getNextRetryTime(),
                event.getSentAt(),
                event.getLastError(),
                event.getCreateTime()
        );
    }
}
