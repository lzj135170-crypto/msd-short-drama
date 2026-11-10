package com.msd.interaction.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 推荐行为事件请求。
 *
 * @param requestId 推荐请求 ID
 * @param dramaId   短剧 ID
 * @param eventType 1 点击，2 播放，3 完播，4 不感兴趣
 * @param position  曝光位置
 */
public record RecommendationEventRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestId,
        @NotNull @Positive Long dramaId,
        @NotNull @Min(1) @Max(4) Integer eventType,
        @Min(0) @Max(100) Integer position
) {
}
