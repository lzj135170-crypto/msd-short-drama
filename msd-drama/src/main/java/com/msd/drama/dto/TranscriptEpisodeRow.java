package com.msd.drama.dto;

/**
 * 字幕导出所需的剧集和短剧可见性信息。
 */
public class TranscriptEpisodeRow {

    // 剧集 ID
    private Long episodeId;
    // 短剧 ID
    private Long dramaId;
    // 短剧标题
    private String dramaTitle;
    // 集数序号
    private Integer episodeNo;
    // 剧集标题
    private String episodeTitle;
    // 解锁所需积分
    private Integer pricePoints;

    /** 获取剧集 ID。 */
    public Long getEpisodeId() {
        return episodeId;
    }

    /** 设置剧集 ID。 */
    public void setEpisodeId(Long episodeId) {
        this.episodeId = episodeId;
    }

    /** 获取短剧 ID。 */
    public Long getDramaId() {
        return dramaId;
    }

    /** 设置短剧 ID。 */
    public void setDramaId(Long dramaId) {
        this.dramaId = dramaId;
    }

    /** 获取短剧标题。 */
    public String getDramaTitle() {
        return dramaTitle;
    }

    /** 设置短剧标题。 */
    public void setDramaTitle(String dramaTitle) {
        this.dramaTitle = dramaTitle;
    }

    /** 获取集数序号。 */
    public Integer getEpisodeNo() {
        return episodeNo;
    }

    /** 设置集数序号。 */
    public void setEpisodeNo(Integer episodeNo) {
        this.episodeNo = episodeNo;
    }

    /** 获取剧集标题。 */
    public String getEpisodeTitle() {
        return episodeTitle;
    }

    /** 设置剧集标题。 */
    public void setEpisodeTitle(String episodeTitle) {
        this.episodeTitle = episodeTitle;
    }

    /** 获取解锁所需积分。 */
    public Integer getPricePoints() {
        return pricePoints;
    }

    /** 设置解锁所需积分。 */
    public void setPricePoints(Integer pricePoints) {
        this.pricePoints = pricePoints;
    }
}
