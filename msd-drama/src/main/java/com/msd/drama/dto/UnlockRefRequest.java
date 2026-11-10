package com.msd.drama.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * drama 解锁引用请求，unlockId 由 order 生成。
 *
 * @param unlockId  订单侧解锁记录 ID
 * @param userId    解锁用户 ID
 */
public record UnlockRefRequest(
        @NotNull @Positive Long unlockId,
        @NotNull @Positive Long userId
) {
}
