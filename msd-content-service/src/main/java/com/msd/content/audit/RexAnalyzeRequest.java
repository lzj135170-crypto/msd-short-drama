package com.msd.content.audit;

import java.util.List;

/**
 * Rex sidecar 分析请求。
 *
 * @param requestId      请求标识，使用 dramaId
 * @param text           标题、副标题和简介拼接文本
 * @param sentimentLabels 情感标签
 * @param tagCandidates   标签候选
 */
public record RexAnalyzeRequest(
        String requestId,
        String text,
        List<String> sentimentLabels,
        List<String> tagCandidates
) {

    /**
     * 统一空值，避免序列化 null 数组。
     */
    public RexAnalyzeRequest {
        sentimentLabels = sentimentLabels == null ? List.of() : List.copyOf(sentimentLabels);
        tagCandidates = tagCandidates == null ? List.of() : List.copyOf(tagCandidates);
    }
}
