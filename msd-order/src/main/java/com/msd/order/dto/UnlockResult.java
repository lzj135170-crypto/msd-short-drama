package com.msd.order.dto;

/**
 * 剧集解锁结果。
 *
 * @param unlockId        解锁记录 ID；免费集为 0
 * @param episodeId       剧集 ID
 * @param pointsCost      本次消耗积分；免费集为 0
 * @param balanceAfter    扣减后余额；免费集为 0
 * @param alreadyUnlocked 是否此前已解锁
 */
public record UnlockResult(
        long unlockId,
        long episodeId,
        long pointsCost,
        long balanceAfter,
        boolean alreadyUnlocked
) {
}
