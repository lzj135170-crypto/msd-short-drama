package com.msd.common.dto;

import java.util.List;

/**
 * 单集字幕导出数据，供 content 建立 transcript 索引。
 *
 * <p>只有在线剧集且所属短剧已上架时才导出。</p>
 *
 * @param dramaId      短剧 ID
 * @param dramaTitle   短剧标题
 * @param episodeId    剧集 ID
 * @param episodeNo    集数序号
 * @param episodeTitle 剧集标题
 * @param pricePoints  解锁所需积分
 * @param segments     带时间戳的片段
 */
public record EpisodeTranscriptDTO(
        Long dramaId,
        String dramaTitle,
        Long episodeId,
        Integer episodeNo,
        String episodeTitle,
        Integer pricePoints,
        List<TranscriptSegmentDTO> segments
) {

    /**
     * 统一空值。
     */
    public EpisodeTranscriptDTO {
        dramaTitle = dramaTitle == null ? "" : dramaTitle;
        episodeTitle = episodeTitle == null ? "" : episodeTitle;
        pricePoints = pricePoints == null ? 0 : pricePoints;
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
