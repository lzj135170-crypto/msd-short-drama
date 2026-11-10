package com.msd.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 积分充值订单实体，对应 t_recharge_order。
 */
@TableName("t_recharge_order")
public class RechargeOrder {

    // 订单 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 业务订单号
    private String orderNo;
    // 用户 ID
    private Long userId;
    // 档位 ID
    private Long planId;
    // 档位名称快照
    private String planName;
    // 实付金额（分）快照
    private Long amountCents;
    // 到账总积分快照
    private Long points;
    // 状态：0 待支付，1 已支付，2 已取消，3 已过期
    private Integer status;
    // 支付渠道：1 MOCK，2 微信，3 支付宝
    private Integer payChannel;
    // 第三方交易号
    private String payTradeNo;
    // 客户端幂等键
    private String clientRequestId;
    // 支付超时时间
    private LocalDateTime expireTime;
    // 支付时间
    private LocalDateTime payTime;
    // 下单 IP
    private String clientIp;
    // 备注
    private String remark;
    // 创建时间
    private LocalDateTime createTime;

    /**
     * 获取订单 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置订单 ID。
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取业务订单号。
     */
    public String getOrderNo() {
        return orderNo;
    }

    /**
     * 设置业务订单号。
     */
    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
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
     * 获取档位 ID。
     */
    public Long getPlanId() {
        return planId;
    }

    /**
     * 设置档位 ID。
     */
    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    /**
     * 获取档位名称快照。
     */
    public String getPlanName() {
        return planName;
    }

    /**
     * 设置档位名称快照。
     */
    public void setPlanName(String planName) {
        this.planName = planName;
    }

    /**
     * 获取实付金额（分）。
     */
    public Long getAmountCents() {
        return amountCents;
    }

    /**
     * 设置实付金额（分）。
     */
    public void setAmountCents(Long amountCents) {
        this.amountCents = amountCents;
    }

    /**
     * 获取到账总积分。
     */
    public Long getPoints() {
        return points;
    }

    /**
     * 设置到账总积分。
     */
    public void setPoints(Long points) {
        this.points = points;
    }

    /**
     * 获取订单状态。
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 设置订单状态。
     */
    public void setStatus(Integer status) {
        this.status = status;
    }

    /**
     * 获取支付渠道。
     */
    public Integer getPayChannel() {
        return payChannel;
    }

    /**
     * 设置支付渠道。
     */
    public void setPayChannel(Integer payChannel) {
        this.payChannel = payChannel;
    }

    /**
     * 获取第三方交易号。
     */
    public String getPayTradeNo() {
        return payTradeNo;
    }

    /**
     * 设置第三方交易号。
     */
    public void setPayTradeNo(String payTradeNo) {
        this.payTradeNo = payTradeNo;
    }

    /**
     * 获取客户端幂等键。
     */
    public String getClientRequestId() {
        return clientRequestId;
    }

    /**
     * 设置客户端幂等键。
     */
    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    /**
     * 获取支付超时时间。
     */
    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    /**
     * 设置支付超时时间。
     */
    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    /**
     * 获取支付时间。
     */
    public LocalDateTime getPayTime() {
        return payTime;
    }

    /**
     * 设置支付时间。
     */
    public void setPayTime(LocalDateTime payTime) {
        this.payTime = payTime;
    }

    /**
     * 获取下单 IP。
     */
    public String getClientIp() {
        return clientIp;
    }

    /**
     * 设置下单 IP。
     */
    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    /**
     * 获取备注。
     */
    public String getRemark() {
        return remark;
    }

    /**
     * 设置备注。
     */
    public void setRemark(String remark) {
        this.remark = remark;
    }

    /**
     * 获取创建时间。
     */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /**
     * 设置创建时间。
     */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
