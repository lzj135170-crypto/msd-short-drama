package com.msd.order.dto;

/**
 * 播放信息；付费未解锁时 videoUrl 为 null。
 *
 * @param dramaId         短剧 ID
 * @param dramaTitle      短剧标题
 * @param episodeId       剧集 ID
 * @param episodeNo       第几集
 * @param episodeTitle    分集标题
 * @param coverUrl        分集封面
 * @param durationSeconds 时长（秒）
 * @param pricePoints     解锁所需积分，0 表示免费
 * @param free            是否免费集
 * @param unlocked        当前用户是否已解锁
 * @param videoUrl        视频地址，仅 unlocked=true 时下发
 */
public record PlaybackInfo(
        Long dramaId,
        String dramaTitle,
        Long episodeId,
        Integer episodeNo,
        String episodeTitle,
        String coverUrl,
        Integer durationSeconds,
        Long pricePoints,
        boolean free,
        boolean unlocked,
        String videoUrl
) {
}
