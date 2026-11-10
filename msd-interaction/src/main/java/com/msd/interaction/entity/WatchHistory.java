package com.msd.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 观看历史实体，对应 t_watch_history。
 */
@TableName("t_watch_history")
public class WatchHistory {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 用户 ID
    private Long userId;
    // 短剧 ID
    private Long dramaId;
    // 剧集 ID
    private Long episodeId;
    // 观看位置，毫秒
    private Integer positionMs;
    // 视频总时长，毫秒
    private Integer durationMs;
    // 是否看完：0 否，1 是
    private Integer finished;
    // 最后观看时间
    private LocalDateTime lastWatchTime;
    // 创建时间
    private LocalDateTime createTime;
    // 更新时间
    private LocalDateTime updateTime;

    /**
     * 获取历史记录 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置历史记录 ID。
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
     * 获取观看位置，毫秒。
     */
    public Integer getPositionMs() {
        return positionMs;
    }

    /**
     * 设置观看位置，毫秒。
     */
    public void setPositionMs(Integer positionMs) {
        this.positionMs = positionMs;
    }

    /**
     * 获取视频总时长，毫秒。
     */
    public Integer getDurationMs() {
        return durationMs;
    }

    /**
     * 设置视频总时长，毫秒。
     */
    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    /**
     * 获取是否看完。
     */
    public Integer getFinished() {
        return finished;
    }

    /**
     * 设置是否看完。
     */
    public void setFinished(Integer finished) {
        this.finished = finished;
    }

    /**
     * 获取最后观看时间。
     */
    public LocalDateTime getLastWatchTime() {
        return lastWatchTime;
    }

    /**
     * 设置最后观看时间。
     */
    public void setLastWatchTime(LocalDateTime lastWatchTime) {
        this.lastWatchTime = lastWatchTime;
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
