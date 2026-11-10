package com.msd.interaction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 观看进度上报请求。
 *
 * @param dramaId    短剧 ID
 * @param episodeId  剧集 ID
 * @param positionMs 观看位置，毫秒
 * @param durationMs 视频总时长，毫秒
 * @param finished   是否看完
 */
public record HistoryRequest(
        @NotNull @Positive Long dramaId,
        @NotNull @Positive Long episodeId,
        @NotNull @Min(0) Integer positionMs,
        @Min(0) Integer durationMs,
        Boolean finished
) {
}
