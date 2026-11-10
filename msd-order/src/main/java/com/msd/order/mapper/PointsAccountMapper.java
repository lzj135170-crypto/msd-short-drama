package com.msd.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.order.entity.PointsAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 积分账户 Mapper。
 */
public interface PointsAccountMapper extends BaseMapper<PointsAccount> {

    /**
     * 锁定积分账户行，供解锁本地事务串行化扣减。
     */
    @Select("SELECT user_id, balance FROM t_points_account WHERE user_id = #{userId} FOR UPDATE")
    PointsAccount selectForUpdate(@Param("userId") Long userId);

    /**
     * 条件扣减积分，余额不足时影响 0 行。
     */
    @Update("UPDATE t_points_account SET balance = balance - #{points} "
            + "WHERE user_id = #{userId} AND balance >= #{points}")
    int deduct(@Param("userId") Long userId, @Param("points") Long points);

    /**
     * 确保积分账户存在；已存在时不修改余额。
     */
    @Insert("INSERT INTO t_points_account(user_id, balance) VALUES(#{userId}, 0) "
            + "ON DUPLICATE KEY UPDATE user_id = user_id")
    int ensureAccount(@Param("userId") Long userId);

    /**
     * 增加积分余额。
     */
    @Update("UPDATE t_points_account SET balance = balance + #{points} "
            + "WHERE user_id = #{userId}")
    int credit(@Param("userId") Long userId, @Param("points") Long points);
}
