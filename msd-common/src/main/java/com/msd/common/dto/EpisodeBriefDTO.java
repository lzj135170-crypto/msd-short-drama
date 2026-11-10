package com.msd.common.dto;

/**
 * 剧集简要信息，用于观看历史和我的评论列表的跨服务补齐。
 *
 * <p>不包含 videoUrl。</p>
 *
 * @param episodeId       剧集 ID
 * @param dramaId         所属短剧 ID
 * @param episodeNo       集数序号
 * @param title           剧集标题
 * @param coverUrl        封面地址
 * @param durationSeconds 时长（秒）
 * @param status          状态：0 下线，1 上线
 */
public record EpisodeBriefDTO(
        Long episodeId,
        Long dramaId,
        Integer episodeNo,
        String title,
        String coverUrl,
        Integer durationSeconds,
        Integer status
) {
}
