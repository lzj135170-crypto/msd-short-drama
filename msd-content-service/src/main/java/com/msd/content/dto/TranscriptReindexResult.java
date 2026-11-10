package com.msd.content.dto;

import java.util.List;

/**
 * 全量重建过程中的字幕索引结果。
 *
 * @param episodes 尝试重建的剧集数
 * @param failed   失败剧集数
 * @param errors   错误摘要
 */
public record TranscriptReindexResult(
        int episodes,
        int failed,
        List<String> errors
) {

    /**
     * 统一空值。
     */
    public TranscriptReindexResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
