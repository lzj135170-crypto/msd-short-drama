package com.msd.order.controller;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import com.msd.order.dto.CreateRechargeOrderRequest;
import com.msd.order.dto.MockPayResult;
import com.msd.order.dto.RechargeOrderView;
import com.msd.order.dto.RechargePlanView;
import com.msd.order.service.RechargeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分充值接口，经网关访问 /api/order/internal/recharge。
 */
@RestController
@RequestMapping("/internal/recharge")
public class RechargeController {

    // 充值服务
    private final RechargeService rechargeService;

    /**
     * 注入充值服务。
     */
    public RechargeController(RechargeService rechargeService) {
        this.rechargeService = rechargeService;
    }

    /**
     * 查询上架充值档位。
     */
    @GetMapping("/plans")
    public Result<List<RechargePlanView>> plans() {
        // 1. 档位为公开配置，不要求用户 ID
        return Result.ok(rechargeService.listPlans());
    }

    /**
     * 创建充值订单；同一 clientRequestId 幂等。
     */
    @PostMapping("/orders")
    public Result<RechargeOrderView> create(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @Valid @RequestBody CreateRechargeOrderRequest request) {
        // 1. 解析用户并创建或返回原订单
        return Result.ok(rechargeService.createOrder(
                RoleGuard.requireUserId(userIdHeader), request));
    }

    /**
     * 查询当前用户的订单。
     */
    @GetMapping("/orders/{orderId}")
    public Result<RechargeOrderView> order(
            @PathVariable("orderId") Long orderId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 只能查询自己的订单
        return Result.ok(rechargeService.getOrder(
                RoleGuard.requireUserId(userIdHeader), orderId));
    }

    /**
     * 模拟支付并到账，重复调用不重复加积分。
     */
    @PostMapping("/orders/{orderId}/mock-pay")
    public Result<MockPayResult> mockPay(
            @PathVariable("orderId") Long orderId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 本地事务完成状态迁移、入账和流水
        return Result.ok(rechargeService.mockPay(
                RoleGuard.requireUserId(userIdHeader), orderId));
    }
}
