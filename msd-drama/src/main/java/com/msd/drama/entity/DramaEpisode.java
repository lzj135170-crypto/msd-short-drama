package com.msd.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 剧集实体，对应 t_drama_episode。
 *
 * <p>不映射 video_url，避免详情接口误把付费视频地址返回给客户端。</p>
 */
@TableName("t_drama_episode")
public class DramaEpisode {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 所属短剧 ID
    private Long dramaId;
    // 集数序号，从 1 开始
    private Integer episodeNo;
    // 剧集标题
    private String title;
    // 剧集封面 URL
    private String coverUrl;
    // 时长（秒）
    private Integer durationSeconds;
    // 解锁所需积分，0 表示免费
    private Long pricePoints;
    // 状态：0 下线，1 上线
    private Integer status;
    // 播放次数
    private Long playCount;
    // 解锁次数
    private Long unlockCount;
    // 点赞数
    private Long likeCount;
    // 评论数
    private Long commentCount;
    // 分集剧情摘要，只描述本集已发生的内容
    private String summary;
    // 摘要状态：0 缺失，1 生成中，2 可用，3 失败
    private Integer summaryStatus;
    // 摘要认领或更新时间
    private LocalDateTime summaryUpdatedAt;

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
     * 获取集数序号。
     */
    public Integer getEpisodeNo() {
        return episodeNo;
    }

    /**
     * 设置集数序号。
     */
    public void setEpisodeNo(Integer episodeNo) {
        this.episodeNo = episodeNo;
    }

    /**
     * 获取剧集标题。
     */
    public String getTitle() {
        return title;
    }

    /**
     * 设置剧集标题。
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 获取剧集封面 URL。
     */
    public String getCoverUrl() {
        return coverUrl;
    }

    /**
     * 设置剧集封面 URL。
     */
    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    /**
     * 获取时长（秒）。
     */
    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    /**
     * 设置时长（秒）。
     */
    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    /**
     * 获取解锁所需积分，0 表示免费。
     */
    public Long getPricePoints() {
        return pricePoints;
    }

    /**
     * 设置解锁所需积分。
     */
    public void setPricePoints(Long pricePoints) {
        this.pricePoints = pricePoints;
    }

    /**
     * 获取状态，1 表示上线。
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 设置状态。
     */
    public void setStatus(Integer status) {
        this.status = status;
    }

    /**
     * 获取播放次数。
     */
    public Long getPlayCount() {
        return playCount;
    }

    /**
     * 设置播放次数。
     */
    public void setPlayCount(Long playCount) {
        this.playCount = playCount;
    }

    /**
     * 获取解锁次数。
     */
    public Long getUnlockCount() {
        return unlockCount;
    }

    /**
     * 设置解锁次数。
     */
    public void setUnlockCount(Long unlockCount) {
        this.unlockCount = unlockCount;
    }

    /**
     * 获取点赞数。
     */
    public Long getLikeCount() {
        return likeCount;
    }

    /**
     * 设置点赞数。
     */
    public void setLikeCount(Long likeCount) {
        this.likeCount = likeCount;
    }

    /**
     * 获取评论数。
     */
    public Long getCommentCount() {
        return commentCount;
    }

    /**
     * 设置评论数。
     */
    public void setCommentCount(Long commentCount) {
        this.commentCount = commentCount;
    }

    /**
     * 获取分集剧情摘要。
     */
    public String getSummary() {
        return summary;
    }

    /**
     * 设置分集剧情摘要。
     */
    public void setSummary(String summary) {
        this.summary = summary;
    }

    /**
     * 获取摘要状态：0 缺失，1 生成中，2 可用，3 失败。
     */
    public Integer getSummaryStatus() {
        return summaryStatus;
    }

    /**
     * 设置摘要状态。
     */
    public void setSummaryStatus(Integer summaryStatus) {
        this.summaryStatus = summaryStatus;
    }

    /**
     * 获取摘要认领或更新时间。
     */
    public LocalDateTime getSummaryUpdatedAt() {
        return summaryUpdatedAt;
    }

    /**
     * 设置摘要认领或更新时间。
     */
    public void setSummaryUpdatedAt(LocalDateTime summaryUpdatedAt) {
        this.summaryUpdatedAt = summaryUpdatedAt;
    }
}
