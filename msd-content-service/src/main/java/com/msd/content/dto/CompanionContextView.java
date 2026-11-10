package com.msd.content.dto;

import java.util.List;

/**
 * AI 伴看前情检索结果。
 *
 * @param currentEpisodeId 当前剧集 ID
 * @param dramaId          所属短剧 ID
 * @param currentEpisodeNo 当前集数
 * @param items            当前集之前的前情，按融合分数降序
 * @param degraded         是否发生降级
 * @param degradedFlags    降级标记，如 drama、vector、bm25、episode_index
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

    /**
     * 统一空值，避免调用方判空。
     */
    public CompanionContextView {
        items = items == null ? List.of() : List.copyOf(items);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
