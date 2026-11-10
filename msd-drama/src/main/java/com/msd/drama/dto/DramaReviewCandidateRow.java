package com.msd.drama.dto;

import java.time.LocalDateTime;

/**
 * 短剧审核候选查询行。
 *
 * <p>只用于 Mapper 结果映射，避免把完整实体暴露给审核接口。</p>
 */
public class DramaReviewCandidateRow {

    // 短剧 ID
    private Long dramaId;
    // 标题
    private String title;
    // 副标题
    private String subtitle;
    // 剧情简介
    private String description;
    // 封面 URL
    private String coverUrl;
    // 主演
    private String actors;
    // 作者 ID
    private Long authorId;
    // 分类 ID
    private Long categoryId;
    // 总集数
    private Integer episodeCount;
    // 人工审核状态
    private Integer reviewStatus;
    // AI 审核状态
    private Integer aiReviewStatus;
    // AI 审核结果 JSON
    private String aiReviewResult;
    // 提交审核时间
    private LocalDateTime submitTime;
    // AI 审核时间
    private LocalDateTime aiReviewTime;
    // 人工审核时间
    private LocalDateTime reviewTime;
    // 上架时间
    private LocalDateTime publishTime;

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
     * 获取标题。
     */
    public String getTitle() {
        return title;
    }

    /**
     * 设置标题。
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 获取副标题。
     */
    public String getSubtitle() {
        return subtitle;
    }

    /**
     * 设置副标题。
     */
    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    /**
     * 获取剧情简介。
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置剧情简介。
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 获取封面 URL。
     */
    public String getCoverUrl() {
        return coverUrl;
    }

    /**
     * 设置封面 URL。
     */
    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    /**
     * 获取主演。
     */
    public String getActors() {
        return actors;
    }

    /**
     * 设置主演。
     */
    public void setActors(String actors) {
        this.actors = actors;
    }

    /**
     * 获取作者 ID。
     */
    public Long getAuthorId() {
        return authorId;
    }

    /**
     * 设置作者 ID。
     */
    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    /**
     * 获取分类 ID。
     */
    public Long getCategoryId() {
        return categoryId;
    }

    /**
     * 设置分类 ID。
     */
    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    /**
     * 获取总集数。
     */
    public Integer getEpisodeCount() {
        return episodeCount;
    }

    /**
     * 设置总集数。
     */
    public void setEpisodeCount(Integer episodeCount) {
        this.episodeCount = episodeCount;
    }

    /**
     * 获取人工审核状态。
     */
    public Integer getReviewStatus() {
        return reviewStatus;
    }

    /**
     * 设置人工审核状态。
     */
    public void setReviewStatus(Integer reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    /**
     * 获取 AI 审核状态。
     */
    public Integer getAiReviewStatus() {
        return aiReviewStatus;
    }

    /**
     * 设置 AI 审核状态。
     */
    public void setAiReviewStatus(Integer aiReviewStatus) {
        this.aiReviewStatus = aiReviewStatus;
    }

    /**
     * 获取 AI 审核结果 JSON。
     */
    public String getAiReviewResult() {
        return aiReviewResult;
    }

    /**
     * 设置 AI 审核结果 JSON。
     */
    public void setAiReviewResult(String aiReviewResult) {
        this.aiReviewResult = aiReviewResult;
    }

    /**
     * 获取提交审核时间。
     */
    public LocalDateTime getSubmitTime() {
        return submitTime;
    }

    /**
     * 设置提交审核时间。
     */
    public void setSubmitTime(LocalDateTime submitTime) {
        this.submitTime = submitTime;
    }

    /**
     * 获取 AI 审核时间。
     */
    public LocalDateTime getAiReviewTime() {
        return aiReviewTime;
    }

    /**
     * 设置 AI 审核时间。
     */
    public void setAiReviewTime(LocalDateTime aiReviewTime) {
        this.aiReviewTime = aiReviewTime;
    }

    /**
     * 获取人工审核时间。
     */
    public LocalDateTime getReviewTime() {
        return reviewTime;
    }

    /**
     * 设置人工审核时间。
     */
    public void setReviewTime(LocalDateTime reviewTime) {
        this.reviewTime = reviewTime;
    }

    /**
     * 获取上架时间。
     */
    public LocalDateTime getPublishTime() {
        return publishTime;
    }

    /**
     * 设置上架时间。
     */
    public void setPublishTime(LocalDateTime publishTime) {
        this.publishTime = publishTime;
    }
}
