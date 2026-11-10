package com.msd.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 积分流水实体，对应 t_points_ledger。
 */
@TableName("t_points_ledger")
public class PointsLedger {

    // 主键，自增
    @TableId(type = IdType.AUTO)
    private Long id;
    // 用户 ID
    private Long userId;
    // 积分变动类型：2 表示消费
    private Integer changeType;
    // 变动积分，扣减为负数
    private Long changePoints;
    // 变动后的余额快照
    private Long balanceAfter;
    // 业务类型：2 表示剧集解锁
    private Integer bizType;
    // 业务关联 ID，指向解锁记录 ID
    private Long bizId;
    // 幂等键，防止同一业务重复记账
    private String idempotencyKey;
    // 流水备注
    private String remark;
    // 记账时间
    private LocalDateTime createTime;

    /**
     * 获取积分流水 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置积分流水 ID。
     */
    public void setId(Long id) {
        this.id = id;
    }

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
     * 获取积分变动类型。
     */
    public Integer getChangeType() {
        return changeType;
    }

    /**
     * 设置积分变动类型。
     */
    public void setChangeType(Integer changeType) {
        this.changeType = changeType;
    }

    /**
     * 获取变动积分。
     */
    public Long getChangePoints() {
        return changePoints;
    }

    /**
     * 设置变动积分。
     */
    public void setChangePoints(Long changePoints) {
        this.changePoints = changePoints;
    }

    /**
     * 获取变动后余额。
     */
    public Long getBalanceAfter() {
        return balanceAfter;
    }

    /**
     * 设置变动后余额。
     */
    public void setBalanceAfter(Long balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    /**
     * 获取业务类型。
     */
    public Integer getBizType() {
        return bizType;
    }

    /**
     * 设置业务类型。
     */
    public void setBizType(Integer bizType) {
        this.bizType = bizType;
    }

    /**
     * 获取业务关联 ID。
     */
    public Long getBizId() {
        return bizId;
    }

    /**
     * 设置业务关联 ID。
     */
    public void setBizId(Long bizId) {
        this.bizId = bizId;
    }

    /**
     * 获取幂等键。
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * 设置幂等键。
     */
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * 获取流水备注。
     */
    public String getRemark() {
        return remark;
    }

    /**
     * 设置流水备注。
     */
    public void setRemark(String remark) {
        this.remark = remark;
    }

    /**
     * 获取记账时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置记账时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
