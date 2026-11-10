package com.msd.interaction.dto;

import java.util.List;

/**
 * 评论课代表视图。
 *
 * @param summary         2-3 句观点摘要
 * @param sentiment       情绪倾向
 * @param representatives 代表评论
 * @param degraded        是否降级
 * @param degradedFlags   降级标记
 * @param elapsedMs       耗时
 */
public record CommentSummaryView(
        String summary,
        String sentiment,
        List<RepresentativeComment> representatives,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs
) {

    /**
     * 统一空值。
     */
    public CommentSummaryView {
        summary = summary == null ? "" : summary;
        sentiment = sentiment == null ? "neutral" : sentiment;
        representatives = representatives == null ? List.of() : List.copyOf(representatives);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }

    /**
     * 代表评论。
     *
     * @param commentId 评论 ID
     * @param content   评论正文
     * @param likeCount 点赞数
     */
    public record RepresentativeComment(Long commentId, String content, Long likeCount) {
    }
}
