package com.msd.order.dto;

import java.time.LocalDateTime;

/**
 * 充值订单视图。
 *
 * @param orderId        订单 ID
 * @param orderNo        业务订单号
 * @param planId         档位 ID
 * @param planName       档位名称快照
 * @param amountCents    实付金额（分）
 * @param points         到账积分
 * @param status         订单状态：0 待支付、1 已支付、2 已取消、3 已过期
 * @param payChannel     支付渠道
 * @param clientRequestId 客户端幂等键
 * @param expireTime     支付超时时间
 * @param payTime        支付时间
 * @param createTime     创建时间
 */
public record RechargeOrderView(
        Long orderId,
        String orderNo,
        Long planId,
        String planName,
        long amountCents,
        long points,
        Integer status,
        Integer payChannel,
        String clientRequestId,
        LocalDateTime expireTime,
        LocalDateTime payTime,
        LocalDateTime createTime
) {
}
