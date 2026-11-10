package com.msd.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * drama 侧解锁引用，unlock_id 来自 order.t_episode_unlock.id。
 */
@TableName("t_episode_unlock_ref")
public class EpisodeUnlockRef {

    // 解锁记录 ID，与 order 侧 t_episode_unlock.id 一致
    @TableId(value = "unlock_id", type = IdType.INPUT)
    private Long unlockId;
    // 剧集 ID
    private Long episodeId;
    // 解锁用户 ID
    private Long userId;
    // 引用创建时间
    private LocalDateTime createTime;

    /**
     * 获取解锁记录 ID。
     */
    public Long getUnlockId() {
        return unlockId;
    }

    /**
     * 设置解锁记录 ID。
     */
    public void setUnlockId(Long unlockId) {
        this.unlockId = unlockId;
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
     * 获取引用创建时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置引用创建时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
