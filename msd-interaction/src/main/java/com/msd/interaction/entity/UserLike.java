package com.msd.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户点赞实体，对应 t_user_like。
 */
@TableName("t_user_like")
public class UserLike {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 用户 ID
    private Long userId;
    // 目标类型：1 短剧，2 剧集，3 评论
    private Integer targetType;
    // 目标 ID
    private Long targetId;
    // 点赞时间
    private LocalDateTime createTime;

    /**
     * 获取点赞记录 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置点赞记录 ID。
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
     * 获取目标类型。
     */
    public Integer getTargetType() {
        return targetType;
    }

    /**
     * 设置目标类型。
     */
    public void setTargetType(Integer targetType) {
        this.targetType = targetType;
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
     * 获取点赞时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置点赞时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
