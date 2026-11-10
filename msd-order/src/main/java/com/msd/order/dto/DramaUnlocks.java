package com.msd.order.dto;

import java.util.List;

/**
 * 用户在某部短剧下已解锁的付费剧集。
 *
 * <p>免费集不写解锁记录，前端用详情里的 free 标记判断。</p>
 *
 * @param dramaId            短剧 ID
 * @param unlockedEpisodeIds 已解锁的付费剧集 ID，按解锁时间升序
 */
public record DramaUnlocks(
        Long dramaId,
        List<Long> unlockedEpisodeIds
) {
}
