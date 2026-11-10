package com.msd.content.dto;

import java.util.List;

/**
 * 当前剧集摘要视图，供 AgentScope 伴看工具和聊天链路使用。
 *
 * @param episodeId     剧集 ID
 * @param dramaId       所属短剧 ID
 * @param episodeNo     集数序号
 * @param episodeTitle  剧集标题
 * @param dramaTitle    短剧标题
 * @param summary       分集摘要，缺失时为空字符串
 * @param source        摘要来源：seed 已有，generated 本次生成，missing 缺失，generating 生成中
 * @param degraded      是否发生降级
 * @param degradedFlags 降级标记，如 drama、summary_model、summary_generation
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

    /**
     * 反序列化和手工构造时统一空值，避免调用方判空。
     */
    public EpisodeSummaryView {
        episodeTitle = episodeTitle == null ? "" : episodeTitle;
        dramaTitle = dramaTitle == null ? "" : dramaTitle;
        summary = summary == null ? "" : summary;
        source = source == null ? "missing" : source;
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
