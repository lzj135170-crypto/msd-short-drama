package com.msd.order.dto;

import java.time.LocalDateTime;

/**
 * 积分流水视图。
 *
 * @param ledgerId     流水 ID
 * @param changeType   变动类型：1 充值、2 解锁、3 人工调整
 * @param changePoints 变动积分，正数入账、负数扣减
 * @param balanceAfter 变动后余额
 * @param bizType      业务类型：1 订单、2 剧集解锁、3 人工
 * @param bizId        业务 ID
 * @param remark       备注
 * @param createTime   记账时间
 */
public record PointsLedgerView(
        Long ledgerId,
        Integer changeType,
        Long changePoints,
        Long balanceAfter,
        Integer bizType,
        Long bizId,
        String remark,
        LocalDateTime createTime
) {
}
