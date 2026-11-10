package com.msd.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Kafka 消费幂等日志实体，对应 t_event_consume_log。
 */
@TableName("t_event_consume_log")
public class EventConsumeLog {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 消费者组
    private String consumerGroup;
    // 事件 ID
    private String eventId;
    // Kafka 主题
    private String topic;
    // 消费时间
    private LocalDateTime consumedAt;

    /**
     * 获取主键 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置主键 ID。
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取消费者组。
     */
    public String getConsumerGroup() {
        return consumerGroup;
    }

    /**
     * 设置消费者组。
     */
    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    /**
     * 获取事件 ID。
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * 设置事件 ID。
     */
    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    /**
     * 获取 Kafka 主题。
     */
    public String getTopic() {
        return topic;
    }

    /**
     * 设置 Kafka 主题。
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }

    /**
     * 获取消费时间。
     */
    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    /**
     * 设置消费时间。
     */
    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }
}
