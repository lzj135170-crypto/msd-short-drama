package com.msd.order.dto;

/**
 * 模拟支付结果。
 *
 * @param orderId    订单 ID
 * @param orderNo    业务订单号
 * @param status     订单状态，支付成功后为 1
 * @param points     本次或历史到账积分
 * @param balance    当前积分余额
 * @param alreadyPaid 是否已支付过，重复调用不会重复加积分
 */
public record MockPayResult(
        Long orderId,
        String orderNo,
        Integer status,
        long points,
        long balance,
        boolean alreadyPaid
) {
}
