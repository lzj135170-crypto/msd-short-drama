package com.msd.interaction.dto;

import java.time.LocalDateTime;

/**
 * 我的评论列表项，包含目标短剧/剧集标题和评论状态。
 *
 * @param commentId    评论 ID
 * @param dramaId      短剧 ID
 * @param dramaTitle   短剧标题
 * @param episodeId    剧集 ID，短剧级评论为空
 * @param episodeTitle 剧集标题
 * @param content      评论内容
 * @param likeCount    点赞数
 * @param replyCount   回复数
 * @param status       状态：0 待审，1 可见，2 隐藏，3 已删除
 * @param createTime   创建时间
 */
public record MyCommentView(
        Long commentId,
        Long dramaId,
        String dramaTitle,
        Long episodeId,
        String episodeTitle,
        String content,
        Long likeCount,
        Long replyCount,
        Integer status,
        LocalDateTime createTime
) {
}
