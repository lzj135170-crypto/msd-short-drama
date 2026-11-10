package com.msd.drama.dto;

/**
 * 首页轮播位。
 *
 * @param dramaId  短剧 ID
 * @param title    短剧标题
 * @param subtitle 副标题
 * @param coverUrl 封面图 URL
 */
public record HomeBanner(
        Long dramaId,
        String title,
        String subtitle,
        String coverUrl
) {
}
