package com.msd.drama.dto;

/**
 * AI 伴看剧集元信息的 MyBatis 查询行。
 *
 * <p>使用普通 POJO 而不是 record，避免依赖构造器参数顺序映射。</p>
 */
public class CompanionEpisodeRow {

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
    // 分集摘要，没有摘要时为空字符串
    private String summary;
    // 摘要状态：0 缺失，1 生成中，2 可用，3 失败
    private Integer summaryStatus;
    // 短剧简介，用于生成摘要提示
    private String dramaDescription;
    // 解锁所需积分，0 表示免费
    private Integer pricePoints;

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
     * 获取分集摘要。
     */
    public String getSummary() {
        return summary;
    }

    /**
     * 设置分集摘要。
     */
    public void setSummary(String summary) {
        this.summary = summary;
    }

    /**
     * 获取摘要状态。
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
     * 获取短剧简介。
     */
    public String getDramaDescription() {
        return dramaDescription;
    }

    /**
     * 设置短剧简介。
     */
    public void setDramaDescription(String dramaDescription) {
        this.dramaDescription = dramaDescription;
    }

    /**
     * 获取解锁所需积分。
     */
    public Integer getPricePoints() {
        return pricePoints;
    }

    /**
     * 设置解锁所需积分。
     */
    public void setPricePoints(Integer pricePoints) {
        this.pricePoints = pricePoints;
    }
}
