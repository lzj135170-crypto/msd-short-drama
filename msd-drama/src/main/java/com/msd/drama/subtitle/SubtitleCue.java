package com.msd.drama.subtitle;

/**
 * 解析后的字幕片段。
 *
 * @param startMs 开始时间（毫秒）
 * @param endMs   结束时间（毫秒）
 * @param text    文本
 */
public record SubtitleCue(int startMs, int endMs, String text) {
}
