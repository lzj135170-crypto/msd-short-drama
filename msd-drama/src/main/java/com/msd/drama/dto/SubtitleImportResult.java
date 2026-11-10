package com.msd.drama.dto;

/**
 * 字幕导入结果。
 *
 * @param subtitleId   字幕 ID
 * @param segmentCount 有效片段数
 * @param filteredCount 被安全过滤或去重丢弃的片段数
 */
public record SubtitleImportResult(Long subtitleId, int segmentCount, int filteredCount) {
}
