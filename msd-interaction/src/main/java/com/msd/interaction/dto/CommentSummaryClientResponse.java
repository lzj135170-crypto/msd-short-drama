package com.msd.interaction.dto;

import java.util.List;

/**
 * content 评论摘要响应。
 *
 * @param summary                  摘要
 * @param sentiment                情绪倾向
 * @param representativeCommentIds 代表评论 ID
 * @param degraded                 是否降级
 * @param degradedFlags            降级标记
 * @param elapsedMs                耗时
 */
public record CommentSummaryClientResponse(
        String summary,
        String sentiment,
        List<Long> representativeCommentIds,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs
) {

    /**
     * 统一空值。
     */
    public CommentSummaryClientResponse {
        summary = summary == null ? "" : summary;
        sentiment = sentiment == null ? "neutral" : sentiment;
        representativeCommentIds = representativeCommentIds == null
                ? List.of() : List.copyOf(representativeCommentIds);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
