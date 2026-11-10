package com.msd.drama.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创作者提交的单集信息。
 *
 * @param episodeNo       集数，从 1 开始
 * @param title           分集标题
 * @param durationSeconds 时长（秒）
 * @param pricePoints     解锁积分，0 表示免费
 * @param status          状态：0 下线，1 上线
 */
public record EpisodeWriteItem(
        @NotNull(message = "集数不能为空")
        @Min(value = 1, message = "集数从 1 开始")
        Integer episodeNo,

        @Size(max = 128, message = "分集标题不能超过 128 字")
        String title,

        @Min(value = 0, message = "时长不能为负数")
        Integer durationSeconds,

        @Min(value = 0, message = "解锁积分不能为负数")
        Long pricePoints,

        @Min(value = 0, message = "剧集状态只能为 0 或 1")
        @Max(value = 1, message = "剧集状态只能为 0 或 1")
        Integer status
) {
}
