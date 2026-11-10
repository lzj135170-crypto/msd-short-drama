package com.msd.common.dto;

import java.util.List;

/**
 * RAG 索引使用的短剧导出数据。
 *
 * <p>只包含已上架短剧；publishTime 使用 ISO 字符串，避免跨服务 Java Time 序列化差异。</p>
 *
 * @param id            短剧 ID
 * @param title         标题
 * @param subtitle      副标题
 * @param coverUrl      封面地址
 * @param description   简介
 * @param categoryId    分类 ID
 * @param categoryName  分类名称
 * @param tags          标签列表
 * @param actors        演员列表
 * @param episodeCount  总集数
 * @param episodeTitles 各集标题，按集数顺序
 * @param publishTime   上架时间，ISO-8601 字符串
 * @param viewCount     播放量
 * @param likeCount     点赞数
 * @param favoriteCount 收藏数
 * @param commentCount  评论数
 * @param episodes      分集明细，包含摘要；用于构建 AI 伴看的前情文档
 */
public record DramaRagExportDTO(
        Long id,
        String title,
        String subtitle,
        String coverUrl,
        String description,
        Long categoryId,
        String categoryName,
        List<String> tags,
        List<String> actors,
        Integer episodeCount,
        List<String> episodeTitles,
        String publishTime,
        Long viewCount,
        Long likeCount,
        Long favoriteCount,
        Long commentCount,
        List<EpisodeRagExportDTO> episodes
) {

    /**
     * 兼容未提供分集明细的调用方，默认使用空列表。
     */
    public DramaRagExportDTO(
            Long id,
            String title,
            String subtitle,
            String coverUrl,
            String description,
            Long categoryId,
            String categoryName,
            List<String> tags,
            List<String> actors,
            Integer episodeCount,
            List<String> episodeTitles,
            String publishTime,
            Long viewCount,
            Long likeCount,
            Long favoriteCount,
            Long commentCount) {
        this(id, title, subtitle, coverUrl, description, categoryId, categoryName,
                tags, actors, episodeCount, episodeTitles, publishTime,
                viewCount, likeCount, favoriteCount, commentCount, List.of());
    }

    /**
     * 反序列化时把缺失或 null 的分集明细归一为空列表。
     */
    public DramaRagExportDTO {
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
    }
}
