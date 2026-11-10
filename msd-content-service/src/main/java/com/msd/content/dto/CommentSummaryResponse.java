package com.msd.content.dto;

import java.util.List;

/**
 * 评论课代表结果。
 *
 * @param summary                  2-3 句观点摘要；失败时为空
 * @param sentiment                情绪倾向：positive/neutral/negative/mixed
 * @param representativeCommentIds 代表评论 ID
 * @param degraded                 是否降级
 * @param degradedFlags            降级标记
 * @param elapsedMs                耗时
 */
public record CommentSummaryResponse(
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
    public CommentSummaryResponse {
        summary = summary == null ? "" : summary;
        sentiment = sentiment == null ? "neutral" : sentiment;
        representativeCommentIds = representativeCommentIds == null
                ? List.of() : List.copyOf(representativeCommentIds);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
