package com.msd.content.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * RAG 推荐请求。
 *
 * @param query 用户自然语言需求，必填，最长 200 个字符
 * @param topK  期望返回的短剧数量，范围 1~3；为空时使用 rerank.top-n
 */
public record RagRecommendRequest(
        @NotBlank(message = "query 不能为空")
        @Size(max = 200, message = "query 最长 200 个字符")
        String query,

        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 3, message = "topK 最大为 3")
        Integer topK
) {
}
