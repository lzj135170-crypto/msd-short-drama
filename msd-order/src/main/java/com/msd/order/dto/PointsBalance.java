package com.msd.order.dto;

/**
 * 用户积分余额。
 *
 * @param userId  用户 ID
 * @param balance 当前余额
 * @param exists  积分账户是否存在
 */
public record PointsBalance(Long userId, Long balance, boolean exists) {
}
