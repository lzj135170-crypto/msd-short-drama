package com.msd.agent.client.dto;

import java.util.List;

/**
 * Agent 伴看工具使用的内容服务只读视图。
 *
 * <p>字段与 content-service 的 /internal/ai/companion 接口 JSON 对齐；
 * Agent 服务不直接依赖 content-service 的 DTO 类。</p>
 */
public final class AgentCompanionViews {

    /**
     * 工具类禁止实例化。
     */
    private AgentCompanionViews() {
    }

    /**
     * 当前剧集摘要。
     *
     * @param episodeId     剧集 ID
     * @param dramaId       所属短剧 ID
     * @param episodeNo     集数序号
     * @param episodeTitle  剧集标题
     * @param dramaTitle    短剧标题
     * @param summary       分集摘要，缺失时为空字符串
     * @param source        摘要来源：seed、generated、missing、generating
     * @param degraded      是否发生降级
     * @param degradedFlags 降级标记
     */
    public record EpisodeSummaryView(
            Long episodeId,
            Long dramaId,
            Integer episodeNo,
            String episodeTitle,
            String dramaTitle,
            String summary,
            String source,
            boolean degraded,
            List<String> degradedFlags
    ) {
    }

    /**
     * 前情检索结果。
     *
     * @param currentEpisodeId 当前剧集 ID
     * @param dramaId          所属短剧 ID
     * @param currentEpisodeNo 当前集数
     * @param items            当前集之前的前情
     * @param degraded         是否发生降级
     * @param degradedFlags    降级标记
     * @param elapsedMs        检索耗时（毫秒）
     */
    public record CompanionContextView(
            Long currentEpisodeId,
            Long dramaId,
            Integer currentEpisodeNo,
            List<ContextItem> items,
            boolean degraded,
            List<String> degradedFlags,
            long elapsedMs
    ) {
    }

    /**
     * 一条前情。
     *
     * @param episodeId        剧集 ID
     * @param episodeNo        集数序号
     * @param episodeTitle     剧集标题
     * @param summary          分集摘要
     * @param score            RRF 融合分数
     * @param retrievalSources 命中的检索来源
     */
    public record ContextItem(
            Long episodeId,
            Integer episodeNo,
            String episodeTitle,
            String summary,
            double score,
            List<String> retrievalSources
    ) {
    }

    /**
     * 前情检索请求。
     *
     * @param query            用户问题或前情关键词
     * @param currentEpisodeId 当前剧集 ID
     * @param topK             期望返回条数
     */
    public record CompanionContextRequest(
            String query,
            Long currentEpisodeId,
            Integer topK
    ) {
    }
}
