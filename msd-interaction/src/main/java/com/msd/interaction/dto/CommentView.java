package com.msd.interaction.dto;

import java.time.LocalDateTime;

/**
 * 评论列表项，包含作者和当前用户的点赞状态。
 *
 * @param commentId      评论 ID
 * @param dramaId        短剧 ID
 * @param episodeId      剧集 ID，短剧级评论为空
 * @param userId         评论人 user_id
 * @param nickname       评论人昵称
 * @param avatarUrl      评论人头像 URL
 * @param rootId         根评论 ID，根评论为 0
 * @param parentId       直接父评论 ID，根评论为 0
 * @param replyToUserId  被回复用户 ID
 * @param replyToNickname 被回复用户昵称
 * @param content        评论内容
 * @param likeCount      点赞数
 * @param replyCount     回复数
 * @param liked          当前用户是否已点赞
 * @param createTime     创建时间
 */
public record CommentView(
        Long commentId,
        Long dramaId,
        Long episodeId,
        Long userId,
        String nickname,
        String avatarUrl,
        Long rootId,
        Long parentId,
        Long replyToUserId,
        String replyToNickname,
        String content,
        Long likeCount,
        Long replyCount,
        boolean liked,
        LocalDateTime createTime
) {
}
