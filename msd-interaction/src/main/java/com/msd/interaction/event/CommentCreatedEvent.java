package com.msd.interaction.event;

/**
 * 评论创建完成事件，事务提交后触发 AI 预审。
 *
 * @param commentId 评论 ID
 */
public record CommentCreatedEvent(Long commentId) {
}
