package com.msd.common.dto;

/**
 * RAG 索引使用的分集导出数据。
 *
 * <p>摘要只描述本集已发生的内容，供 AI 伴看检索当前集之前的前情。</p>
 *
 * @param id          剧集 ID
 * @param episodeNo   集数序号，从 1 开始
 * @param title       剧集标题，可能为空字符串
 * @param summary     分集摘要，可能为空字符串
 * @param pricePoints 解锁所需积分，0 表示免费
 */
public record EpisodeRagExportDTO(
        Long id,
        Integer episodeNo,
        String title,
        String summary,
        Integer pricePoints
) {

    /**
     * 兼容旧调用方，默认按免费集处理。
     */
    public EpisodeRagExportDTO(Long id, Integer episodeNo, String title, String summary) {
        this(id, episodeNo, title, summary, 0);
    }
}
