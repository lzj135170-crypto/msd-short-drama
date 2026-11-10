package com.msd.drama.dto;

/**
 * 剧集播放源，只允许服务间直连获取。
 *
 * <p>包含 videoUrl，不能通过网关暴露给客户端。</p>
 *
 * @param episodeId       剧集 ID
 * @param dramaId         所属短剧 ID
 * @param dramaTitle      短剧标题
 * @param episodeNo       集数序号
 * @param episodeTitle    剧集标题
 * @param videoUrl        视频播放地址
 * @param coverUrl        封面图 URL
 * @param durationSeconds 时长（秒）
 * @param pricePoints     解锁所需积分
 */
public record EpisodeSource(
        Long episodeId,
        Long dramaId,
        String dramaTitle,
        Integer episodeNo,
        String episodeTitle,
        String videoUrl,
        String coverUrl,
        Integer durationSeconds,
        Long pricePoints
) {
}
