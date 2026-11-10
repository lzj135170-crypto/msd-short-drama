package com.msd.content.audit;

import java.util.List;

/**
 * RexUniNLU 审核建议，只作为 ai_review_result.advisory 持久化。
 *
 * <p>不参与 aiReviewStatus 判定，也不写标签关系表。</p>
 */
public record DramaReviewAdvisory(
        String model,
        String revision,
        DramaSentimentAdvisory sentiment,
        List<DramaTagSuggestion> tags,
        int chunks,
        int failedChunks,
        boolean truncated,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs
) {

    /**
     * 统一空值，避免写入 null 数组。
     */
    public DramaReviewAdvisory {
        tags = tags == null ? List.of() : List.copyOf(tags);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }

    /**
     * 构造降级建议；模型不可用时也能给审核台明确提示。
     */
    public static DramaReviewAdvisory degraded(String flag, long elapsedMs) {
        return new DramaReviewAdvisory(
                "", "",
                new DramaSentimentAdvisory("unknown", "", false, 0, List.of()),
                List.of(), 0, 0, false, true,
                flag == null || flag.isBlank() ? List.of("rex_unavailable") : List.of(flag),
                Math.max(elapsedMs, 0));
    }

    /**
     * 去掉所有证据片段，用于 JSON 超限时一级裁剪。
     */
    public DramaReviewAdvisory withoutEvidence() {
        DramaSentimentAdvisory sentimentWithoutEvidence = sentiment == null
                ? new DramaSentimentAdvisory("unknown", "", false, 0, List.of())
                : new DramaSentimentAdvisory(sentiment.label(), sentiment.rawLabel(),
                sentiment.highNegative(), sentiment.support(), List.of());
        List<DramaTagSuggestion> tagsWithoutEvidence = tags.stream()
                .map(tag -> new DramaTagSuggestion(tag.name(), tag.support(), List.of()))
                .toList();
        return new DramaReviewAdvisory(model, revision, sentimentWithoutEvidence,
                tagsWithoutEvidence, chunks, failedChunks, truncated, degraded,
                degradedFlags, elapsedMs);
    }

    /**
     * 去掉标签建议，用于 JSON 超限时二级裁剪。
     */
    public DramaReviewAdvisory withoutTags() {
        return new DramaReviewAdvisory(model, revision, sentiment, List.of(),
                chunks, failedChunks, truncated, degraded, degradedFlags, elapsedMs);
    }
}
