package com.msd.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * AI 回答反馈，对应 t_ai_feedback。
 */
@TableName("t_ai_feedback")
public class AiFeedback {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 反馈用户
    private Long userId;
    // 场景：RAG_SEARCH/COMPANION/AUDIT/RECOMMEND
    private String scene;
    // AI 回答请求 ID
    private String requestId;
    // 1 有用，-1 没用
    private Integer rating;
    // 结构化原因
    private String reason;
    // 用户补充说明
    private String comment;
    // 关联目标 ID，如短剧或剧集
    private Long targetId;
    // 会话 ID
    private String sessionId;
    // 创建时间
    private LocalDateTime createTime;

    /**
     * 获取反馈 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置反馈 ID。
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取用户 ID。
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 设置用户 ID。
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /**
     * 获取场景。
     */
    public String getScene() {
        return scene;
    }

    /**
     * 设置场景。
     */
    public void setScene(String scene) {
        this.scene = scene;
    }

    /**
     * 获取 requestId。
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 设置 requestId。
     */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * 获取评分。
     */
    public Integer getRating() {
        return rating;
    }

    /**
     * 设置评分。
     */
    public void setRating(Integer rating) {
        this.rating = rating;
    }

    /**
     * 获取原因。
     */
    public String getReason() {
        return reason;
    }

    /**
     * 设置原因。
     */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * 获取补充说明。
     */
    public String getComment() {
        return comment;
    }

    /**
     * 设置补充说明。
     */
    public void setComment(String comment) {
        this.comment = comment;
    }

    /**
     * 获取目标 ID。
     */
    public Long getTargetId() {
        return targetId;
    }

    /**
     * 设置目标 ID。
     */
    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    /**
     * 获取会话 ID。
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 设置会话 ID。
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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
}
