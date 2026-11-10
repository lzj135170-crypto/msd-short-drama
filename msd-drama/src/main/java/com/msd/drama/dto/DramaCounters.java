package com.msd.drama.dto;

/**
 * 短剧冗余计数，由 Kafka 消费端异步更新。
 *
 * @param viewCount     播放/浏览数
 * @param likeCount     点赞数
 * @param favoriteCount 收藏数
 * @param commentCount  评论数
 */
public record DramaCounters(
        Long viewCount,
        Long likeCount,
        Long favoriteCount,
        Long commentCount
) {
}
