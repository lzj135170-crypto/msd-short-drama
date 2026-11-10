package com.msd.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.order.entity.RechargeOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 充值订单 Mapper。
 */
public interface RechargeOrderMapper extends BaseMapper<RechargeOrder> {

    /**
     * 按用户和客户端幂等键查询订单。
     */
    @Select("""
            SELECT id, order_no, user_id, plan_id, plan_name, amount_cents, points,
                   status, pay_channel, pay_trade_no, client_request_id, expire_time,
                   pay_time, client_ip, remark, create_time
            FROM t_recharge_order
            WHERE user_id = #{userId} AND client_request_id = #{clientRequestId}
            """)
    RechargeOrder selectByUserAndRequest(@Param("userId") Long userId,
                                         @Param("clientRequestId") String clientRequestId);

    /**
     * 按用户和订单 ID 查询订单。
     */
    @Select("""
            SELECT id, order_no, user_id, plan_id, plan_name, amount_cents, points,
                   status, pay_channel, pay_trade_no, client_request_id, expire_time,
                   pay_time, client_ip, remark, create_time
            FROM t_recharge_order
            WHERE id = #{orderId} AND user_id = #{userId}
            """)
    RechargeOrder selectByIdAndUser(@Param("orderId") Long orderId,
                                    @Param("userId") Long userId);

    /**
     * 锁定订单行，供模拟支付串行化。
     */
    @Select("""
            SELECT id, order_no, user_id, plan_id, plan_name, amount_cents, points,
                   status, pay_channel, pay_trade_no, client_request_id, expire_time,
                   pay_time, client_ip, remark, create_time
            FROM t_recharge_order
            WHERE id = #{orderId} AND user_id = #{userId}
            FOR UPDATE
            """)
    RechargeOrder selectByIdAndUserForUpdate(@Param("orderId") Long orderId,
                                             @Param("userId") Long userId);

    /**
     * 待支付 -> 已支付；重复支付影响 0 行。
     */
    @Update("""
            UPDATE t_recharge_order
            SET status = 1, pay_trade_no = #{payTradeNo}, pay_time = NOW(3)
            WHERE id = #{orderId} AND user_id = #{userId} AND status = 0
            """)
    int markPaid(@Param("orderId") Long orderId,
                 @Param("userId") Long userId,
                 @Param("payTradeNo") String payTradeNo);

    /**
     * 待支付且已超时 -> 已过期。
     */
    @Update("""
            UPDATE t_recharge_order
            SET status = 3
            WHERE id = #{orderId} AND user_id = #{userId} AND status = 0
              AND expire_time < NOW(3)
            """)
    int expireIfPending(@Param("orderId") Long orderId,
                        @Param("userId") Long userId);
}
