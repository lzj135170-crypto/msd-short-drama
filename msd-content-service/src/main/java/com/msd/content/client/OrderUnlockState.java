package com.msd.content.client;

/**
 * 订单服务返回的剧集解锁状态。
 *
 * <p>字段与 msd-order 的 UnlockState 保持一致，避免 content-service 依赖订单模块。</p>
 *
 * @param episodeId  剧集 ID
 * @param unlocked   是否已解锁
 * @param unlockId   解锁记录 ID，未解锁时为 0
 * @param pointsCost 解锁消耗积分，未解锁时为 0
 */
public record OrderUnlockState(
        Long episodeId,
        boolean unlocked,
        Long unlockId,
        Long pointsCost
) {
}
