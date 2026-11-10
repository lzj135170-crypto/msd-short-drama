package com.msd.interaction.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 点赞/取消点赞请求。
 *
 * @param targetType 目标类型：1 短剧，2 剧集，3 评论
 * @param targetId   目标 ID
 */
public record LikeRequest(
        @NotNull @Min(1) @Max(3) Integer targetType,
        @NotNull @Positive Long targetId
) {
}
