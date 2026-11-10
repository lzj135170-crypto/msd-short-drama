package com.msd.content.client;

import java.util.List;

/**
 * 订单服务返回的短剧已解锁付费集。
 *
 * <p>免费集不写解锁记录，调用方仍需用 pricePoints 判断免费/付费。</p>
 *
 * @param dramaId            短剧 ID
 * @param unlockedEpisodeIds 已解锁的付费剧集 ID
 */
public record OrderDramaUnlocks(
        Long dramaId,
        List<Long> unlockedEpisodeIds
) {

    /**
     * 统一空列表，避免调用方判空。
     */
    public OrderDramaUnlocks {
        unlockedEpisodeIds = unlockedEpisodeIds == null
                ? List.of() : List.copyOf(unlockedEpisodeIds);
    }
}
