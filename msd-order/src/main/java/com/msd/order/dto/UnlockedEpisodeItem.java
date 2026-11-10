package com.msd.order.dto;

import java.time.LocalDateTime;

/**
 * 已解锁剧集列表项，剧集和短剧信息由 drama 批量接口补齐。
 *
 * @param unlockId        解锁记录 ID
 * @param dramaId         短剧 ID
 * @param dramaTitle      短剧标题
 * @param dramaCoverUrl   短剧封面
 * @param episodeId       剧集 ID
 * @param episodeNo       集数
 * @param episodeTitle    剧集标题
 * @param episodeCoverUrl 剧集封面
 * @param pointsCost      解锁消耗积分
 * @param unlockTime      解锁时间
 */
public record UnlockedEpisodeItem(
        Long unlockId,
        Long dramaId,
        String dramaTitle,
        String dramaCoverUrl,
        Long episodeId,
        Integer episodeNo,
        String episodeTitle,
        String episodeCoverUrl,
        Long pointsCost,
        LocalDateTime unlockTime
) {
}
