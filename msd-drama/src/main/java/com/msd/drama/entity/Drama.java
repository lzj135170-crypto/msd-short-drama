package com.msd.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 短剧实体，对应 t_drama。
 *
 * <p>公开查询必须显式过滤 deleted=0 和 review_status=2，实体没有配置逻辑删除。</p>
 */
@TableName("t_drama")
public class Drama {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 短剧标题
    private String title;
    // 副标题或一句话简介
    private String subtitle;
    // 封面图 URL
    private String coverUrl;
    // 剧情简介
    private String description;
    // 所属分类 ID
    private Long categoryId;
    // 作者/创作者 ID
    private Long authorId;
    // 主演列表，通常以逗号分隔
    private String actors;
    // 总集数
    private Integer episodeCount;
    // 审核状态：2 表示审核通过并已上架
    private Integer reviewStatus;
    // 上架时间，为空表示尚未上架
    private LocalDateTime publishTime;
    // 逻辑删除标记：0 正常，1 已删除
    private Integer deleted;
    // 累计播放/浏览数
    private Long viewCount;
    // 累计点赞数
    private Long likeCount;
    // 累计收藏数
    private Long favoriteCount;
    // 累计评论数
    private Long commentCount;
    // 人工审核人 ID
    private Long reviewerId;
    // 人工审核意见
    private String reviewRemark;
    // 提交审核时间
    private LocalDateTime submitTime;
    // 人工审核时间
    private LocalDateTime reviewTime;
    // 下架时间
    private LocalDateTime offlineTime;
    // AI 审核状态：0 未提交、1 待审核、2 通过、3 驳回、4 失败
    private Integer aiReviewStatus;
    // AI 审核结果 JSON
    private String aiReviewResult;
    // AI 审核时间
    private LocalDateTime aiReviewTime;

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
     * 获取短剧标题。
     */
    public String getTitle() {
        return title;
    }

    /**
     * 设置短剧标题。
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
     * 获取所属分类 ID。
     */
    public Long getCategoryId() {
        return categoryId;
    }

    /**
     * 设置所属分类 ID。
     */
    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    /**
     * 获取作者/创作者 ID。
     */
    public Long getAuthorId() {
        return authorId;
    }

    /**
     * 设置作者/创作者 ID。
     */
    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    /**
     * 获取主演列表。
     */
    public String getActors() {
        return actors;
    }

    /**
     * 设置主演列表。
     */
    public void setActors(String actors) {
        this.actors = actors;
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
     * 获取审核状态，2 表示审核通过。
     */
    public Integer getReviewStatus() {
        return reviewStatus;
    }

    /**
     * 设置审核状态。
     */
    public void setReviewStatus(Integer reviewStatus) {
        this.reviewStatus = reviewStatus;
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

    /**
     * 获取逻辑删除标记，0 表示正常。
     */
    public Integer getDeleted() {
        return deleted;
    }

    /**
     * 设置逻辑删除标记。
     */
    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    /**
     * 获取累计播放/浏览数。
     */
    public Long getViewCount() {
        return viewCount;
    }

    /**
     * 设置累计播放/浏览数。
     */
    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
    }

    /**
     * 获取累计点赞数。
     */
    public Long getLikeCount() {
        return likeCount;
    }

    /**
     * 设置累计点赞数。
     */
    public void setLikeCount(Long likeCount) {
        this.likeCount = likeCount;
    }

    /**
     * 获取累计收藏数。
     */
    public Long getFavoriteCount() {
        return favoriteCount;
    }

    /**
     * 设置累计收藏数。
     */
    public void setFavoriteCount(Long favoriteCount) {
        this.favoriteCount = favoriteCount;
    }

    /**
     * 获取累计评论数。
     */
    public Long getCommentCount() {
        return commentCount;
    }

    /**
     * 设置累计评论数。
     */
    public void setCommentCount(Long commentCount) {
        this.commentCount = commentCount;
    }

    /**
     * 获取人工审核人 ID。
     */
    public Long getReviewerId() {
        return reviewerId;
    }

    /**
     * 设置人工审核人 ID。
     */
    public void setReviewerId(Long reviewerId) {
        this.reviewerId = reviewerId;
    }

    /**
     * 获取人工审核意见。
     */
    public String getReviewRemark() {
        return reviewRemark;
    }

    /**
     * 设置人工审核意见。
     */
    public void setReviewRemark(String reviewRemark) {
        this.reviewRemark = reviewRemark;
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
     * 获取下架时间。
     */
    public LocalDateTime getOfflineTime() {
        return offlineTime;
    }

    /**
     * 设置下架时间。
     */
    public void setOfflineTime(LocalDateTime offlineTime) {
        this.offlineTime = offlineTime;
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
}
