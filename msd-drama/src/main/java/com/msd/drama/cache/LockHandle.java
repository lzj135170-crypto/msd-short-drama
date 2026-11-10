package com.msd.drama.cache;

import org.redisson.api.RLock;

/**
 * 分布式锁句柄；UNAVAILABLE/BUSY 时 lock 为 null。
 *
 * @param attempt 获取锁的结果
 * @param lock    Redisson 锁对象；未获取成功时为 null
 */
public record LockHandle(LockAttempt attempt, RLock lock) {

    /**
     * 构造成功获取锁的句柄。
     */
    public static LockHandle acquired(RLock lock) {
        return new LockHandle(LockAttempt.ACQUIRED, lock);
    }

    /**
     * 构造锁被占用的结果。
     */
    public static LockHandle busy() {
        return new LockHandle(LockAttempt.BUSY, null);
    }

    /**
     * 构造 Redis 不可用的结果。
     */
    public static LockHandle unavailable() {
        return new LockHandle(LockAttempt.UNAVAILABLE, null);
    }
}
