package com.msd.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * content-service 写回短剧 AI 审核结果的请求。
 *
 * @param aiReviewStatus AI 审核状态：2 通过、3 驳回、4 失败
 * @param aiReviewResult 完整审核结果 JSON
 */
public record DramaAiReviewResultRequest(
        @NotNull(message = "AI 审核状态不能为空")
        @Min(value = 2, message = "AI 审核状态只能为 2-4")
        @Max(value = 4, message = "AI 审核状态只能为 2-4")
        Integer aiReviewStatus,

        @NotBlank(message = "AI 审核结果不能为空")
        @Size(max = 8000, message = "AI 审核结果不能超过 8000 字")
        String aiReviewResult
) {
}
