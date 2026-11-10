package com.msd.order.dto;

/**
 * drama 播放源的本地副本，字段与 drama 的 EpisodeSource 一致。
 *
 * @param episodeId      剧集 ID
 * @param dramaId        短剧 ID
 * @param dramaTitle     短剧标题
 * @param episodeNo      第几集
 * @param episodeTitle   分集标题
 * @param videoUrl       视频地址，仅用于服务间传递
 * @param coverUrl       分集封面
 * @param durationSeconds 时长（秒）
 * @param pricePoints    解锁所需积分，0 表示免费
 */
public record EpisodeSourceDTO(
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
