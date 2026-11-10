package com.msd.content.dto;

import java.util.List;

/**
 * 字幕检索结果。
 *
 * @param items         当前播放位置之前的字幕片段
 * @param degraded      是否降级
 * @param degradedFlags 降级标记
 * @param elapsedMs     检索耗时（毫秒）
 */
public record TranscriptContextView(
        List<TranscriptCitation> items,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs
) {

    /**
     * 统一空值。
     */
    public TranscriptContextView {
        items = items == null ? List.of() : List.copyOf(items);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }

    /**
     * 空视图。
     */
    public static TranscriptContextView empty(long elapsedMs) {
        return new TranscriptContextView(List.of(), false, List.of(), elapsedMs);
    }
}
