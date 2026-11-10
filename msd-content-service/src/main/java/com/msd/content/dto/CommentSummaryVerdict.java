package com.msd.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 模型返回的评论摘要结构。
 *
 * @param summary                  2-3 句中文摘要
 * @param sentiment                情绪倾向
 * @param representativeCommentIds 代表评论 ID
 */
public record CommentSummaryVerdict(
        @JsonProperty("summary")
        @JsonPropertyDescription("2-3 句中文观点摘要")
        String summary,

        @JsonProperty("sentiment")
        @JsonPropertyDescription("positive/neutral/negative/mixed")
        String sentiment,

        @JsonProperty("representative_comment_ids")
        @JsonPropertyDescription("2-3 条代表评论 ID")
        List<Long> representativeCommentIds
) {

    /**
     * 统一空值。
     */
    public CommentSummaryVerdict {
        summary = summary == null ? "" : summary;
        sentiment = sentiment == null ? "neutral" : sentiment;
        representativeCommentIds = representativeCommentIds == null
                ? List.of() : List.copyOf(representativeCommentIds);
    }
}
