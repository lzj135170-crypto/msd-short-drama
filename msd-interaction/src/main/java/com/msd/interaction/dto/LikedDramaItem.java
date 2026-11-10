package com.msd.interaction.dto;

import java.time.LocalDateTime;

/**
 * 点赞短剧列表项，短剧信息由 drama 批量接口补齐。
 *
 * @param dramaId      短剧 ID
 * @param title        短剧标题
 * @param subtitle     短剧副标题
 * @param coverUrl     封面 URL
 * @param episodeCount 剧集数
 * @param likedTime    点赞时间
 */
public record LikedDramaItem(
        Long dramaId,
        String title,
        String subtitle,
        String coverUrl,
        Integer episodeCount,
        LocalDateTime likedTime
) {
}
