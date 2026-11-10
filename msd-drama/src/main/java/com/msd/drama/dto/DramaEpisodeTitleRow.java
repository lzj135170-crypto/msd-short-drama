package com.msd.drama.dto;

/**
 * 短剧剧集导出批量查询行。
 *
 * <p>同时携带剧集 ID 和摘要，供 RAG 导出构建分集文档。</p>
 */
public class DramaEpisodeTitleRow {

    // 剧集 ID
    private Long episodeId;
    // 短剧 ID
    private Long dramaId;
    // 集数序号
    private Integer episodeNo;
    // 剧集标题
    private String title;
    // 分集摘要，没有摘要时为空字符串
    private String summary;
    // 摘要状态：0 缺失，1 生成中，2 可用，3 失败
    private Integer summaryStatus;
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
