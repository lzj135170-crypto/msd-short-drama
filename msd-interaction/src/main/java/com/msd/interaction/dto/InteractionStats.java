package com.msd.interaction.dto;

/**
 * 个人中心互动计数。
 *
 * @param likedDramaCount 点赞短剧数
 * @param favoriteCount   收藏数
 * @param commentCount    评论数
 * @param historyCount    观看历史条数
 */
public record InteractionStats(
        Long likedDramaCount,
        Long favoriteCount,
        Long commentCount,
        Long historyCount
) {
}
