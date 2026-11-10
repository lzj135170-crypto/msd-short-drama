package com.msd.content.dto;

/**
 * 伴看回答引用的资料。
 *
 * @param type         来源类型：current 当前集摘要，previous 前情，transcript 字幕片段
 * @param episodeId    剧集 ID
 * @param episodeNo    集数序号
 * @param episodeTitle 剧集标题
 * @param summary      摘要正文或字幕文本
 * @param startMs      字幕片段开始时间（毫秒），非字幕来源为 null
 * @param endMs        字幕片段结束时间（毫秒），非字幕来源为 null
 */
public record CompanionSource(
        String type,
        Long episodeId,
        Integer episodeNo,
        String episodeTitle,
        String summary,
        Integer startMs,
        Integer endMs
) {

    /**
     * 统一空值。
     */
    public CompanionSource {
        type = type == null ? "" : type;
        episodeTitle = episodeTitle == null ? "" : episodeTitle;
        summary = summary == null ? "" : summary;
    }

    /**
     * 兼容旧调用方，摘要来源没有时间轴。
     */
    public CompanionSource(String type, Long episodeId, Integer episodeNo,
                           String episodeTitle, String summary) {
        this(type, episodeId, episodeNo, episodeTitle, summary, null, null);
    }
}
