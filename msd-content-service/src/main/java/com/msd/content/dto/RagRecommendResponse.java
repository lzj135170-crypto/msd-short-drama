package com.msd.content.dto;

import java.util.List;
import java.util.UUID;

/**
 * RAG 推荐响应。
 *
 * @param answer         推荐回答文本；检索为空、超时或生成失败时返回固定兜底文案
 * @param rewrittenQuery 模型改写后的检索查询，未改写时为 null
 * @param sources        引用的短剧来源，按相关度排序并截断到 topK
 * @param degraded       本次推荐是否发生降级
 * @param degradedFlags  降级原因标记，如 generation、timeout
 * @param elapsedMs      推荐耗时（毫秒）
 * @param requestId      本次请求标识，用于反馈和日志关联
 * @param cached         是否命中精确/语义缓存
 * @param variant        A/B 实验变体，未参与实验时为 null
 */
public record RagRecommendResponse(
        String answer,
        String rewrittenQuery,
        List<RagSource> sources,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs,
        String requestId,
        boolean cached,
        String variant
) {

    /**
     * 兼容旧调用方，并保证 requestId 和列表始终非空。
     */
    public RagRecommendResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
    }

    /**
     * 兼容没有 requestId/cached/variant 的旧构造调用。
     */
    public RagRecommendResponse(String answer, String rewrittenQuery, List<RagSource> sources,
                                boolean degraded, List<String> degradedFlags, long elapsedMs) {
        this(answer, rewrittenQuery, sources, degraded, degradedFlags, elapsedMs, null,
                false, null);
    }

    /**
     * 兼容没有 cached/variant 的旧构造调用。
     */
    public RagRecommendResponse(String answer, String rewrittenQuery, List<RagSource> sources,
                                boolean degraded, List<String> degradedFlags, long elapsedMs,
                                String requestId) {
        this(answer, rewrittenQuery, sources, degraded, degradedFlags, elapsedMs, requestId,
                false, null);
    }

    /**
     * 缓存命中时替换请求 ID 和耗时，保留原回答、来源和变体。
     */
    public RagRecommendResponse withCacheHit(String newRequestId) {
        return new RagRecommendResponse(answer, rewrittenQuery, sources, degraded,
                degradedFlags, 0, newRequestId, true, variant);
    }

    /**
     * 补充 A/B 变体，保留其他字段。
     */
    public RagRecommendResponse withVariant(String newVariant) {
        return new RagRecommendResponse(answer, rewrittenQuery, sources, degraded,
                degradedFlags, elapsedMs, requestId, cached, newVariant);
    }
}
