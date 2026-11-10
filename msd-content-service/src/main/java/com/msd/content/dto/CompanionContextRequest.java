package com.msd.content.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * AI 伴看前情检索请求。
 *
 * @param query            用户问题或前情关键词
 * @param currentEpisodeId 当前正在观看的剧集 ID
 * @param topK             期望返回条数，为空时使用配置默认值
 */
public record CompanionContextRequest(
        @NotBlank @Size(max = 500) String query,
        @NotNull @Min(1) Long currentEpisodeId,
        @Min(1) @Max(10) Integer topK
) {
}
