package com.msd.drama.dto;

import java.time.LocalDateTime;

/**
 * 首页短剧卡片。
 *
 * @param dramaId       短剧 ID
 * @param title         短剧标题
 * @param subtitle      副标题
 * @param coverUrl      封面图 URL
 * @param categoryId    分类 ID
 * @param categoryName  分类名称
 * @param actors        主演列表
 * @param episodeCount  总集数
 * @param viewCount     播放/浏览数
 * @param likeCount     点赞数
 * @param favoriteCount 收藏数
 * @param commentCount  评论数
 * @param publishTime   上架时间
 */
public record DramaCard(
        Long dramaId,
        String title,
        String subtitle,
        String coverUrl,
        Long categoryId,
        String categoryName,
        String actors,
        Integer episodeCount,
        Long viewCount,
        Long likeCount,
        Long favoriteCount,
        Long commentCount,
        LocalDateTime publishTime
) {
}
