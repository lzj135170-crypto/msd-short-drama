package com.msd.interaction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 推荐曝光批量上报，服务间直连调用。
 *
 * @param userId       用户 ID
 * @param requestId    推荐请求 ID
 * @param scene        场景，默认 HOME_FEED
 * @param algorithm    算法版本
 * @param experimentId 实验 ID
 * @param variant      实验分桶
 * @param items        曝光条目
 */
public record RecommendationExposureRequest(
        @NotNull @Positive Long userId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestId,
        @Size(max = 32) String scene,
        @Size(max = 32) String algorithm,
        @Size(max = 64) String experimentId,
        @Size(max = 32) String variant,
        @NotNull @Size(max = 20) List<@Valid Item> items
) {

    /**
     * 一条曝光。
     *
     * @param dramaId  短剧 ID
     * @param position 展示位置，从 1 开始
     * @param reason   推荐理由
     */
    public record Item(
            @NotNull @Positive Long dramaId,
            @Min(0) @Max(100) Integer position,
            @Size(max = 256) String reason
    ) {
    }
}
