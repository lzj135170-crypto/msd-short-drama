package com.msd.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 剧集解锁权益实体，对应 t_episode_unlock。
 */
@TableName("t_episode_unlock")
public class EpisodeUnlock {

    // 主键，自增
    @TableId(type = IdType.AUTO)
    private Long id;
    // 解锁用户 ID
    private Long userId;
    // 所属短剧 ID
    private Long dramaId;
    // 剧集 ID
    private Long episodeId;
    // 解锁时消耗的积分快照
    private Long pointsCost;
    // 解锁时间
    private LocalDateTime createTime;

    /**
     * 获取解锁记录 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置解锁记录 ID。
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取解锁用户 ID。
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 设置解锁用户 ID。
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /**
     * 获取所属短剧 ID。
     */
    public Long getDramaId() {
        return dramaId;
    }

    /**
     * 设置所属短剧 ID。
     */
    public void setDramaId(Long dramaId) {
        this.dramaId = dramaId;
    }

    /**
     * 获取剧集 ID。
     */
    public Long getEpisodeId() {
        return episodeId;
    }

    /**
     * 设置剧集 ID。
     */
    public void setEpisodeId(Long episodeId) {
        this.episodeId = episodeId;
    }

    /**
     * 获取解锁消耗的积分。
     */
    public Long getPointsCost() {
        return pointsCost;
    }

    /**
     * 设置解锁消耗的积分。
     */
    public void setPointsCost(Long pointsCost) {
        this.pointsCost = pointsCost;
    }

    /**
     * 获取解锁时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置解锁时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
