package com.msd.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 推荐点击、播放、完播和负反馈事件，对应 t_recommendation_event。
 */
@TableName("t_recommendation_event")
public class RecommendationEvent {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;
    // 推荐请求 ID
    private String requestId;
    // 用户 ID
    private Long userId;
    // 短剧 ID
    private Long dramaId;
    // 1 点击，2 播放，3 完播，4 不感兴趣
    private Integer eventType;
    // 曝光位置
    private Integer position;
    // 事件时间
    private LocalDateTime createTime;

    /** 主键。 */
    public Long getId() {
        return id;
    }

    /** 设置主键。 */
    public void setId(Long id) {
        this.id = id;
    }

    /** 推荐请求 ID。 */
    public String getRequestId() {
        return requestId;
    }

    /** 设置推荐请求 ID。 */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /** 用户 ID。 */
    public Long getUserId() {
        return userId;
    }

    /** 设置用户 ID。 */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** 短剧 ID。 */
    public Long getDramaId() {
        return dramaId;
    }

    /** 设置短剧 ID。 */
    public void setDramaId(Long dramaId) {
        this.dramaId = dramaId;
    }

    /** 事件类型。 */
    public Integer getEventType() {
        return eventType;
    }

    /** 设置事件类型。 */
    public void setEventType(Integer eventType) {
        this.eventType = eventType;
    }

    /** 曝光位置。 */
    public Integer getPosition() {
        return position;
    }

    /** 设置曝光位置。 */
    public void setPosition(Integer position) {
        this.position = position;
    }

    /** 事件时间。 */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /** 设置事件时间。 */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
