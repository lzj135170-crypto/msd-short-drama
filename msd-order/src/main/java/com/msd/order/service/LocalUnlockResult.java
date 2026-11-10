package com.msd.order.service;

/**
 * 本地解锁事务结果。
 *
 * @param unlockId        解锁记录 ID
 * @param balanceAfter    扣减后余额；已解锁时为当前余额
 * @param alreadyUnlocked 是否此前已解锁
 */
public record LocalUnlockResult(
        long unlockId,
        long balanceAfter,
        boolean alreadyUnlocked
) {
}
