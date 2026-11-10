package com.msd.drama.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 短剧详情。
 *
 * @param id           短剧 ID
 * @param title        短剧标题
 * @param subtitle     副标题
 * @param coverUrl     封面图 URL
 * @param description  剧情简介
 * @param category     所属分类
 * @param tags         标签列表
 * @param actors       主演列表
 * @param authorId     作者/创作者 ID
 * @param episodeCount 总集数
 * @param publishTime  上架时间
 * @param counters     冗余计数
 * @param episodes     剧集列表
 */
public record DramaDetail(
        Long id,
        String title,
        String subtitle,
        String coverUrl,
        String description,
        CategoryView category,
        List<TagView> tags,
        List<String> actors,
        Long authorId,
        Integer episodeCount,
        LocalDateTime publishTime,
        DramaCounters counters,
        List<EpisodeItem> episodes
) {
}
