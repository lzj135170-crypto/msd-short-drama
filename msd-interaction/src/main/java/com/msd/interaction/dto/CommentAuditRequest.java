package com.msd.interaction.dto;

/**
 * 调用 content 结构化审核接口的请求。
 *
 * @param content 待审核评论
 * @param scene   固定为 comment
 */
public record CommentAuditRequest(String content, String scene) {
}
