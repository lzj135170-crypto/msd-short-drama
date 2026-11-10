package com.msd.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户收藏实体，对应 t_user_favorite。
 */
@TableName("t_user_favorite")
public class UserFavorite {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 用户 ID
    private Long userId;
    // 短剧 ID
    private Long dramaId;
    // 收藏时间
    private LocalDateTime createTime;

    /**
     * 获取收藏记录 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置收藏记录 ID。
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
     * 获取短剧 ID。
     */
    public Long getDramaId() {
        return dramaId;
    }

    /**
     * 设置短剧 ID。
     */
    public void setDramaId(Long dramaId) {
        this.dramaId = dramaId;
    }

    /**
     * 获取收藏时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置收藏时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
