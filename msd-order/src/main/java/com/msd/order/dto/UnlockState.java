package com.msd.order.dto;

/**
 * 用户对某集的解锁状态。
 *
 * @param episodeId 剧集 ID
 * @param unlocked  是否已解锁
 * @param unlockId  解锁记录 ID，未解锁时为 0
 * @param pointsCost 解锁消耗积分，未解锁时为 0
 */
public record UnlockState(Long episodeId, boolean unlocked, Long unlockId, Long pointsCost) {
}
