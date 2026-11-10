package com.msd.agent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * content RAG 推荐接口的 Agent 侧视图。
 */
public final class AgentRagViews {

    /**
     * 工具类禁止实例化。
     */
    private AgentRagViews() {
    }

    /**
     * RAG 推荐响应。
     *
     * @param answer         推荐回答
     * @param rewrittenQuery 改写后的检索词
     * @param sources        引用短剧
     * @param degraded       是否降级
     * @param degradedFlags  降级标记
     * @param elapsedMs      耗时
     * @param requestId      请求 ID
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RagRecommendView(
            String answer,
            String rewrittenQuery,
            List<RagSourceView> sources,
            boolean degraded,
            List<String> degradedFlags,
            long elapsedMs,
            String requestId
    ) {

        /**
         * 统一空值。
         */
        public RagRecommendView {
            answer = answer == null ? "" : answer;
            sources = sources == null ? List.of() : List.copyOf(sources);
            degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
        }
    }

    /**
     * RAG 来源短剧。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RagSourceView(
            Long dramaId,
            String title,
            String subtitle,
            String coverUrl,
            String snippet,
            Double score
    ) {

        /**
         * 统一空值。
         */
        public RagSourceView {
            title = title == null ? "" : title;
            subtitle = subtitle == null ? "" : subtitle;
            coverUrl = coverUrl == null ? "" : coverUrl;
            snippet = snippet == null ? "" : snippet;
        }
    }
}
