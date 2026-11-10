package com.msd.content.dto;

import java.util.List;

/**
 * 一条字幕引用，带可 seek 的时间戳。
 *
 * @param episodeId        剧集 ID
 * @param episodeNo        集数序号
 * @param episodeTitle     剧集标题
 * @param startMs          开始时间（毫秒）
 * @param endMs            结束时间（毫秒）
 * @param text             字幕文本，已截断
 * @param score            融合分数
 * @param retrievalSources 命中的检索来源
 * @param pricePoints      解锁所需积分
 */
public record TranscriptCitation(
        Long episodeId,
        Integer episodeNo,
        String episodeTitle,
        Integer startMs,
        Integer endMs,
        String text,
        double score,
        List<String> retrievalSources,
        Integer pricePoints
) {

    /**
     * 统一空值。
     */
    public TranscriptCitation {
        episodeTitle = episodeTitle == null ? "" : episodeTitle;
        startMs = startMs == null ? 0 : startMs;
        endMs = endMs == null ? startMs : endMs;
        text = text == null ? "" : text;
        retrievalSources = retrievalSources == null ? List.of() : List.copyOf(retrievalSources);
        pricePoints = pricePoints == null ? 0 : pricePoints;
    }
}
