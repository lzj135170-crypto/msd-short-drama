package com.msd.content.dto;

import java.util.List;

/**
 * 前情提要/本集看点视图。
 *
 * @param dramaId       短剧 ID
 * @param episodeNo     当前集数
 * @param current       当前集摘要；付费未解锁或不存时为 null
 * @param previous      当前集之前、用户可看的前情摘要，按集数升序
 * @param degraded      是否发生降级
 * @param degradedFlags 降级标记
 */
public record RecapView(
        Long dramaId,
        Integer episodeNo,
        RecapItem current,
        List<RecapItem> previous,
        boolean degraded,
        List<String> degradedFlags
) {

    /**
     * 统一空列表。
     */
    public RecapView {
        previous = previous == null ? List.of() : List.copyOf(previous);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
