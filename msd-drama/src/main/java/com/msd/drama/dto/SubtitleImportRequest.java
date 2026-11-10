package com.msd.drama.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创作者字幕导入请求。
 *
 * <p>Demo 直接提交 SRT/VTT 文本；生产可换成对象存储上传后提交 objectKey。</p>
 *
 * @param language 语言，默认 zh
 * @param format   SRT 或 VTT
 * @param content  字幕文本
 */
public record SubtitleImportRequest(
        @Size(max = 16, message = "语言不能超过 16 字") String language,
        @NotBlank(message = "字幕格式不能为空") String format,
        @NotBlank(message = "字幕内容不能为空")
        @Size(max = 2_000_000, message = "字幕内容不能超过 2MB") String content
) {

    /**
     * 默认中文。
     */
    public SubtitleImportRequest {
        language = language == null || language.isBlank() ? "zh" : language.trim();
        format = format == null ? "" : format.trim();
    }
}
