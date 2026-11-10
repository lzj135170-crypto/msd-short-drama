package com.msd.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 评论实体，对应 t_comment。
 */
@TableName("t_comment")
public class Comment {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 短剧 ID
    private Long dramaId;
    // 剧集 ID，短剧级评论为空
    private Long episodeId;
    // 评论人 user_id
    private Long userId;
    // 根评论 ID，根评论为 0
    private Long rootId;
    // 直接父评论 ID，根评论为 0
    private Long parentId;
    // 回复目标用户 ID
    private Long replyToUserId;
    // 评论内容
    private String content;
    // 客户端请求幂等键
    private String clientRequestId;
    // 点赞数（冗余）
    private Long likeCount;
    // 回复数（冗余，只记在根评论）
    private Long replyCount;
    // 状态：0 待审，1 可见，2 隐藏，3 已删除
    private Integer status;
    // AI 审核状态：0 未审核，1 审核中，2 通过，3 风险，4 失败
    private Integer aiReviewStatus;
    // AI 审核结果 JSON，仅作建议
    private String aiReviewResult;
    // AI 审核时间
    private LocalDateTime aiReviewTime;
    // 创建时间
    private LocalDateTime createTime;
    // 更新时间
    private LocalDateTime updateTime;

    /**
     * 获取评论 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置评论 ID。
     */
    public void setId(Long id) {
        this.id = id;
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
     * 获取评论人 user_id。
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 设置评论人 user_id。
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /**
     * 获取根评论 ID。
     */
    public Long getRootId() {
        return rootId;
    }

    /**
     * 设置根评论 ID。
     */
    public void setRootId(Long rootId) {
        this.rootId = rootId;
    }

    /**
     * 获取直接父评论 ID。
     */
    public Long getParentId() {
        return parentId;
    }

    /**
     * 设置直接父评论 ID。
     */
    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    /**
     * 获取被回复用户 ID。
     */
    public Long getReplyToUserId() {
        return replyToUserId;
    }

    /**
     * 设置被回复用户 ID。
     */
    public void setReplyToUserId(Long replyToUserId) {
        this.replyToUserId = replyToUserId;
    }

    /**
     * 获取评论内容。
     */
    public String getContent() {
        return content;
    }

    /**
     * 设置评论内容。
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 获取客户端请求幂等键。
     */
    public String getClientRequestId() {
        return clientRequestId;
    }

    /**
     * 设置客户端请求幂等键。
     */
    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
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
     * 获取回复数。
     */
    public Long getReplyCount() {
        return replyCount;
    }

    /**
     * 设置回复数。
     */
    public void setReplyCount(Long replyCount) {
        this.replyCount = replyCount;
    }

    /**
     * 获取评论状态。
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 设置评论状态。
     */
    public void setStatus(Integer status) {
        this.status = status;
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
