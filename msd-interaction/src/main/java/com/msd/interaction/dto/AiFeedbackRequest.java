package com.msd.interaction.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * AI 回答反馈请求。
 *
 * @param scene     场景：RAG_SEARCH/COMPANION/AUDIT/RECOMMEND/CREATOR_COPY
 * @param requestId AI 回答请求 ID
 * @param rating    1 有用，-1 没用
 * @param reason    结构化原因，可为空
 * @param comment   用户补充说明，可为空
 * @param targetId  关联目标 ID，可为空
 * @param sessionId 会话 ID，可为空
 */
public record AiFeedbackRequest(
        @NotBlank @Size(max = 32) String scene,
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(-1) @Max(1) Integer rating,
        @Size(max = 64) String reason,
        @Size(max = 500) String comment,
        @Positive Long targetId,
        @Size(max = 64) String sessionId
) {
}
