package com.msd.drama.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * ASR worker 回传的字幕片段。
 *
 * @param language 语言，默认 zh
 * @param segments 带时间戳的片段
 */
public record AsrTranscriptRequest(
        @Size(max = 16, message = "语言不能超过 16 字") String language,
        @NotEmpty(message = "字幕片段不能为空")
        @Size(max = 2_000, message = "字幕片段不能超过 2000 条")
        List<@Valid Segment> segments
) {

    /**
     * 默认中文并统一空值。
     */
    public AsrTranscriptRequest {
        language = language == null || language.isBlank() ? "zh" : language.trim();
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /**
     * 单个 ASR 片段。
     *
     * @param startMs 开始时间（毫秒）
     * @param endMs   结束时间（毫秒）
     * @param text    文本
     */
    public record Segment(
            Integer startMs,
            Integer endMs,
            @Size(max = 1000, message = "单条字幕不能超过 1000 字") String text
    ) {
    }
}
