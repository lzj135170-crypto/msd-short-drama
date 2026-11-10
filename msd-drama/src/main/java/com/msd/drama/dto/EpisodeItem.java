package com.msd.drama.dto;

/**
 * 详情页剧集条目，不包含视频地址。
 *
 * @param episodeId       剧集 ID
 * @param episodeNo       集数序号
 * @param title           剧集标题
 * @param coverUrl        封面图 URL
 * @param durationSeconds 时长（秒）
 * @param pricePoints     解锁所需积分
 * @param free            是否免费
 * @param playCount       播放次数
 * @param likeCount       点赞数
 * @param commentCount    评论数
 * @param unlockCount     解锁次数
 */
public record EpisodeItem(
        Long episodeId,
        Integer episodeNo,
        String title,
        String coverUrl,
        Integer durationSeconds,
        Long pricePoints,
        boolean free,
        Long playCount,
        Long likeCount,
        Long commentCount,
        Long unlockCount
) {
}
