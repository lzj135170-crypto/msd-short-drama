package com.msd.content.audit;

import java.util.List;

/**
 * 持久化到 ai_review_result 的情感建议。
 *
 * @param label        positive / negative / mixed / unknown
 * @param rawLabel     模型原始情感标签
 * @param highNegative 负向片段是否达到人工复核阈值
 * @param support      命中片数，不是模型概率
 * @param evidence     证据片段
 */
public record DramaSentimentAdvisory(
        String label,
        String rawLabel,
        boolean highNegative,
        int support,
        List<String> evidence
) {

    /**
     * 统一空值，避免写入 null 数组。
     */
    public DramaSentimentAdvisory {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
