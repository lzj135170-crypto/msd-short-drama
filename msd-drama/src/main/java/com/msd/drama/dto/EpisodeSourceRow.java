package com.msd.drama.dto;

/**
 * 剧集播放源的 MyBatis 查询行。
 *
 * <p>使用普通 POJO 而不是 record，避免依赖构造器参数顺序映射。</p>
 */
public class EpisodeSourceRow {

    // 剧集 ID
    private Long episodeId;
    // 所属短剧 ID
    private Long dramaId;
    // 短剧标题
    private String dramaTitle;
    // 集数序号
    private Integer episodeNo;
    // 剧集标题
    private String episodeTitle;
    // 视频播放地址
    private String videoUrl;
    // 封面图 URL
    private String coverUrl;
    // 时长（秒）
    private Integer durationSeconds;
    // 解锁所需积分
    private Long pricePoints;

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
     * 获取短剧标题。
     */
    public String getDramaTitle() {
        return dramaTitle;
    }

    /**
     * 设置短剧标题。
     */
    public void setDramaTitle(String dramaTitle) {
        this.dramaTitle = dramaTitle;
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
    public String getEpisodeTitle() {
        return episodeTitle;
    }

    /**
     * 设置剧集标题。
     */
    public void setEpisodeTitle(String episodeTitle) {
        this.episodeTitle = episodeTitle;
    }

    /**
     * 获取视频播放地址。
     */
    public String getVideoUrl() {
        return videoUrl;
    }

    /**
     * 设置视频播放地址。
     */
    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    /**
     * 获取封面图 URL。
     */
    public String getCoverUrl() {
        return coverUrl;
    }

    /**
     * 设置封面图 URL。
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
     * 获取解锁所需积分。
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
}
