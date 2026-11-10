package com.msd.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 积分充值档位实体，对应 t_recharge_plan。
 */
@TableName("t_recharge_plan")
public class RechargePlan {

    // 档位 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 档位编码
    private String planCode;
    // 档位名称
    private String name;
    // 价格，单位：分
    private Long priceCents;
    // 基础积分
    private Long basePoints;
    // 赠送积分
    private Long bonusPoints;
    // 排序值，越小越靠前
    private Integer sort;
    // 状态：0 下架，1 上架
    private Integer status;

    /**
     * 获取档位 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置档位 ID。
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取档位编码。
     */
    public String getPlanCode() {
        return planCode;
    }

    /**
     * 设置档位编码。
     */
    public void setPlanCode(String planCode) {
        this.planCode = planCode;
    }

    /**
     * 获取档位名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 设置档位名称。
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取价格（分）。
     */
    public Long getPriceCents() {
        return priceCents;
    }

    /**
     * 设置价格（分）。
     */
    public void setPriceCents(Long priceCents) {
        this.priceCents = priceCents;
    }

    /**
     * 获取基础积分。
     */
    public Long getBasePoints() {
        return basePoints;
    }

    /**
     * 设置基础积分。
     */
    public void setBasePoints(Long basePoints) {
        this.basePoints = basePoints;
    }

    /**
     * 获取赠送积分。
     */
    public Long getBonusPoints() {
        return bonusPoints;
    }

    /**
     * 设置赠送积分。
     */
    public void setBonusPoints(Long bonusPoints) {
        this.bonusPoints = bonusPoints;
    }

    /**
     * 获取排序值。
     */
    public Integer getSort() {
        return sort;
    }

    /**
     * 设置排序值。
     */
    public void setSort(Integer sort) {
        this.sort = sort;
    }

    /**
     * 获取状态。
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 设置状态。
     */
    public void setStatus(Integer status) {
        this.status = status;
    }
}
