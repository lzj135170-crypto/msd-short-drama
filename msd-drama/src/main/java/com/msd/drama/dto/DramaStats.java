package com.msd.drama.dto;

/**
 * 短剧统计信息，供测试台展示异步计数。
 *
 * @param dramaId       短剧 ID
 * @param title         短剧标题
 * @param episodeCount  总集数
 * @param reviewStatus  审核状态，2 表示已上架
 * @param viewCount     播放/浏览数
 * @param likeCount     点赞数
 * @param favoriteCount 收藏数
 * @param commentCount  评论数
 */
public record DramaStats(
        Long dramaId,
        String title,
        Integer episodeCount,
        Integer reviewStatus,
        Long viewCount,
        Long likeCount,
        Long favoriteCount,
        Long commentCount
) {
}
