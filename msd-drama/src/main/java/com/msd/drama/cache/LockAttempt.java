package com.msd.drama.cache;

/**
 * 获取分布式锁的结果。
 */
public enum LockAttempt {
    /** 成功获取锁 */
    ACQUIRED,
    /** 锁被其他线程或实例持有 */
    BUSY,
    /** Redis 不可用，无法判断锁状态 */
    UNAVAILABLE
}
