package com.msd.order.dto;

/**
 * 调用 drama 写解锁引用的请求。
 *
 * @param unlockId 订单侧解锁记录 ID
 * @param userId   解锁用户 ID
 */
public record UnlockRefRequest(Long unlockId, Long userId) {
}
