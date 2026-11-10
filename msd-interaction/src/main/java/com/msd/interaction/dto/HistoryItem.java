package com.msd.interaction.dto;

import java.time.LocalDateTime;

/**
 * 观看历史列表项，短剧和剧集信息由 drama 批量接口补齐。
 *
 * @param dramaId        短剧 ID
 * @param dramaTitle     短剧标题
 * @param dramaCoverUrl  短剧封面 URL
 * @param episodeId      剧集 ID
 * @param episodeNo      剧集序号
 * @param episodeTitle   剧集标题
 * @param episodeCoverUrl 剧集封面 URL
 * @param positionMs     观看位置，毫秒
 * @param durationMs     视频总时长，毫秒
 * @param finished       是否看完
 * @param lastWatchTime  最后观看时间
 */
public record HistoryItem(
        Long dramaId,
        String dramaTitle,
        String dramaCoverUrl,
        Long episodeId,
        Integer episodeNo,
        String episodeTitle,
        String episodeCoverUrl,
        Integer positionMs,
        Integer durationMs,
        boolean finished,
        LocalDateTime lastWatchTime
) {
}
