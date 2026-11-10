package com.msd.interaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建评论/回复请求。
 *
 * @param dramaId   短剧 ID
 * @param episodeId 剧集 ID，短剧级评论为空
 * @param rootId    根评论 ID，根评论为 0 或空
 * @param parentId       直接父评论 ID，根评论为 0 或空
 * @param content        评论内容
 * @param clientRequestId 客户端请求幂等键，重试时必须复用
 */
public record CommentRequest(
        @NotNull @Positive Long dramaId,
        @Positive Long episodeId,
        Long rootId,
        Long parentId,
        @NotBlank @Size(max = 1000) String content,
        @Size(max = 64) String clientRequestId
) {
}
