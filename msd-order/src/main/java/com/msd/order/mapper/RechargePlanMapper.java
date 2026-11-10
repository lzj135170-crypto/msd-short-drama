package com.msd.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.order.entity.RechargePlan;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 充值档位 Mapper。
 */
public interface RechargePlanMapper extends BaseMapper<RechargePlan> {

    /**
     * 查询上架档位，按排序值和 ID 升序。
     */
    @Select("""
            SELECT id, plan_code, name, price_cents, base_points, bonus_points, sort, status
            FROM t_recharge_plan
            WHERE status = 1
            ORDER BY sort, id
            """)
    List<RechargePlan> selectActive();
}
