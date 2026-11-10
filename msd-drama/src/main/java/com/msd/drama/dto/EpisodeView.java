package com.msd.drama.dto;

/**
 * 剧集列表与计数字段，供测试台展示。
 *
 * @param episodeId   剧集 ID
 * @param dramaId     所属短剧 ID
 * @param episodeNo   集数序号
 * @param title       剧集标题
 * @param pricePoints 解锁所需积分，0 表示免费
 * @param status      状态：0 下线，1 上线
 * @param playCount   播放次数
 * @param likeCount   点赞数
 * @param commentCount 评论数
 * @param unlockCount 解锁次数
 */
public record EpisodeView(
        Long episodeId,
        Long dramaId,
        Integer episodeNo,
        String title,
        Long pricePoints,
        Integer status,
        Long playCount,
        Long likeCount,
        Long commentCount,
        Long unlockCount
) {
}
