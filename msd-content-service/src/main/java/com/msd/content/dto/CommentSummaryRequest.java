package com.msd.content.dto;

import java.util.List;

/**
 * 评论课代表请求，由 interaction 聚合高赞评论后提交。
 *
 * @param dramaId   短剧 ID
 * @param episodeId 剧集 ID，短剧级评论为空
 * @param comments  最多 50 条评论
 */
public record CommentSummaryRequest(
        Long dramaId,
        Long episodeId,
        List<CommentInput> comments
) {

    /**
     * 一条待总结评论。
     *
     * @param commentId 评论 ID
     * @param content   评论正文
     * @param likeCount 点赞数
     */
    public record CommentInput(Long commentId, String content, Long likeCount) {
    }
}
