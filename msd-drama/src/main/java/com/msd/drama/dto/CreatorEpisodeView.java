package com.msd.drama.dto;

/**
 * 创作者编辑页使用的剧集视图。
 *
 * @param episodeId       剧集 ID
 * @param episodeNo       集数
 * @param title           标题
 * @param coverUrl        封面 URL
 * @param durationSeconds 时长（秒）
 * @param pricePoints     解锁积分
 * @param status          状态：0 下线，1 上线
 */
public record CreatorEpisodeView(
        Long episodeId,
        Integer episodeNo,
        String title,
        String coverUrl,
        Integer durationSeconds,
        Long pricePoints,
        Integer status
) {
}
