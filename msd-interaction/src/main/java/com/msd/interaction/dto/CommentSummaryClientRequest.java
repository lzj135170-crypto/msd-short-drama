package com.msd.interaction.dto;

import java.util.List;

/**
 * 调用 content 评论摘要接口的请求体。
 *
 * @param dramaId   短剧 ID
 * @param episodeId 剧集 ID
 * @param comments  评论列表
 */
public record CommentSummaryClientRequest(
        Long dramaId,
        Long episodeId,
        List<CommentInput> comments
) {

    /**
     * 待总结评论。
     *
     * @param commentId 评论 ID
     * @param content   评论正文
     * @param likeCount 点赞数
     */
    public record CommentInput(Long commentId, String content, Long likeCount) {
    }
}
