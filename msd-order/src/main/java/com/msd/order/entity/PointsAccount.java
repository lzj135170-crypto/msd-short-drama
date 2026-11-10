package com.msd.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 积分账户实体，对应 t_points_account。
 */
@TableName("t_points_account")
public class PointsAccount {

    // 用户 ID，作为主键由业务写入
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;
    // 当前积分余额
    private Long balance;

    /**
     * 获取用户 ID。
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 设置用户 ID。
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /**
     * 获取积分余额。
     */
    public Long getBalance() {
        return balance;
    }

    /**
     * 设置积分余额。
     */
    public void setBalance(Long balance) {
        this.balance = balance;
    }
}
