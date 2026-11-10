package com.msd.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.order.entity.PointsLedger;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 积分流水 Mapper。
 */
public interface PointsLedgerMapper extends BaseMapper<PointsLedger> {

    /**
     * 分页查询用户积分流水，按时间倒序。
     */
    @Select("""
            SELECT id, user_id, change_type, change_points, balance_after,
                   biz_type, biz_id, idempotency_key, remark, create_time
            FROM t_points_ledger
            WHERE user_id = #{userId}
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PointsLedger> selectPageByUser(@Param("userId") Long userId,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    /**
     * 统计用户积分流水数量。
     */
    @Select("SELECT COUNT(*) FROM t_points_ledger WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);
}
