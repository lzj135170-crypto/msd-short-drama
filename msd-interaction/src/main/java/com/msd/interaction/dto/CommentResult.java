package com.msd.interaction.dto;

/**
 * 创建评论结果。
 *
 * @param commentId 新评论 ID
 * @param eventId   评论事件 ID
 */
public record CommentResult(long commentId, String eventId) {
}
