package com.msd.content.dto;

/**
 * 一条前情提要。
 *
 * @param episodeId   剧集 ID
 * @param episodeNo   集数序号
 * @param title       剧集标题
 * @param summary     分集摘要
 */
public record RecapItem(
        Long episodeId,
        Integer episodeNo,
        String title,
        String summary
) {

    /**
     * 统一空值，避免前端判空。
     */
    public RecapItem {
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
    }
}
