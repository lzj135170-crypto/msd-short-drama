package com.msd.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 事务性发件箱实体，对应 t_outbox_event。
 */
@TableName("t_outbox_event")
public class OutboxEvent {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 事件 UUID，消费端幂等键
    private String eventId;
    // 触发事件的用户 ID，用于事件归属校验
    private Long userId;
    // Kafka Topic
    private String topic;
    // 事件类型：LIKE/FAVORITE/COMMENT/HISTORY/DRAMA_CHANGED/AI_REVIEW_REQUEST
    private String eventType;
    // 聚合类型：DRAMA/EPISODE/COMMENT/ORDER
    private String aggregateType;
    // 聚合根 ID
    private Long aggregateId;
    // Kafka 分区键
    private String eventKey;
    /**
     * MySQL JSON 列直接用 String 读写，避免 JacksonTypeHandler 二次编码。
     */
    private String payload;
    // 状态：0 待发送，1 已发送，2 死信
    private Integer status;
    // 重试次数
    private Integer retryCount;
    // 下次重试时间
    private LocalDateTime nextRetryTime;
    // 发送成功时间
    private LocalDateTime sentAt;
    // 最后一次错误
    private String lastError;
    // 创建时间
    private LocalDateTime createTime;
    // 更新时间
    private LocalDateTime updateTime;

    /**
     * 获取事件记录 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置事件记录 ID。
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取事件 UUID。
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * 设置事件 UUID。
     */
    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    /**
     * 获取事件归属用户。
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 设置事件归属用户。
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /**
     * 获取 Kafka Topic。
     */
    public String getTopic() {
        return topic;
    }

    /**
     * 设置 Kafka Topic。
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }

    /**
     * 获取事件类型。
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * 设置事件类型。
     */
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    /**
     * 获取聚合类型。
     */
    public String getAggregateType() {
        return aggregateType;
    }

    /**
     * 设置聚合类型。
     */
    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    /**
     * 获取聚合根 ID。
     */
    public Long getAggregateId() {
        return aggregateId;
    }

    /**
     * 设置聚合根 ID。
     */
    public void setAggregateId(Long aggregateId) {
        this.aggregateId = aggregateId;
    }

    /**
     * 获取 Kafka 分区键。
     */
    public String getEventKey() {
        return eventKey;
    }

    /**
     * 设置 Kafka 分区键。
     */
    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    /**
     * 获取事件体 JSON 字符串。
     */
    public String getPayload() {
        return payload;
    }

    /**
     * 设置事件体 JSON 字符串。
     */
    public void setPayload(String payload) {
        this.payload = payload;
    }

    /**
     * 获取投递状态。
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 设置投递状态。
     */
    public void setStatus(Integer status) {
        this.status = status;
    }

    /**
     * 获取重试次数。
     */
    public Integer getRetryCount() {
        return retryCount;
    }

    /**
     * 设置重试次数。
     */
    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    /**
     * 获取下次重试时间。
     */
    public LocalDateTime getNextRetryTime() {
        return nextRetryTime;
    }

    /**
     * 设置下次重试时间。
     */
    public void setNextRetryTime(LocalDateTime nextRetryTime) {
        this.nextRetryTime = nextRetryTime;
    }

    /**
     * 获取发送成功时间。
     */
    public LocalDateTime getSentAt() {
        return sentAt;
    }

    /**
     * 设置发送成功时间。
     */
    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    /**
     * 获取最后一次错误信息。
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 设置最后一次错误信息。
     */
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * 获取创建时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置创建时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    /**
     * 获取更新时间。
     */
    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    /**
     * 设置更新时间。
     */
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
