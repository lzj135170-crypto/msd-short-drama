package com.msd.common.dto;

/**
 * 带时间戳的字幕片段，供剧情问答引用和播放器 seek。
 *
 * @param id      片段 ID
 * @param startMs 开始时间（毫秒）
 * @param endMs   结束时间（毫秒）
 * @param text    字幕文本
 */
public record TranscriptSegmentDTO(
        Long id,
        Integer startMs,
        Integer endMs,
        String text
) {

    /**
     * 统一空值。
     */
    public TranscriptSegmentDTO {
        startMs = startMs == null ? 0 : Math.max(startMs, 0);
        endMs = endMs == null ? startMs : Math.max(endMs, startMs);
        text = text == null ? "" : text;
    }
}
