package com.msd.order.dto;

/**
 * 充值档位视图。
 *
 * @param planId      档位 ID
 * @param planCode    档位编码
 * @param name        档位名称
 * @param priceCents  价格（分）
 * @param basePoints  基础积分
 * @param bonusPoints 赠送积分
 * @param totalPoints 到账总积分
 */
public record RechargePlanView(
        Long planId,
        String planCode,
        String name,
        long priceCents,
        long basePoints,
        long bonusPoints,
        long totalPoints
) {
}
