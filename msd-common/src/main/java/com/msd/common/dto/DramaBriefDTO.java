package com.msd.common.dto;

/**
 * 短剧简要信息，用于收藏、历史、点赞等列表的跨服务补齐。
 *
 * @param dramaId      短剧 ID
 * @param title        标题
 * @param subtitle     副标题
 * @param coverUrl     封面地址
 * @param episodeCount 总集数
 * @param categoryId   分类 ID
 */
public record DramaBriefDTO(
        Long dramaId,
        String title,
        String subtitle,
        String coverUrl,
        Integer episodeCount,
        Long categoryId
) {
}
