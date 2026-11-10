package com.msd.order.controller;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.order.dto.PointsLedgerView;
import com.msd.order.service.RechargeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 积分流水查询接口。
 */
@RestController
@RequestMapping("/internal/orders")
public class PointsLedgerController {

    // 充值服务
    private final RechargeService rechargeService;

    /**
     * 注入充值服务。
     */
    public PointsLedgerController(RechargeService rechargeService) {
        this.rechargeService = rechargeService;
    }

    /**
     * 分页查询当前用户积分流水。
     */
    @GetMapping("/points/ledger")
    public Result<PageResult<PointsLedgerView>> ledger(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        // 1. 只返回当前用户自己的流水
        return Result.ok(rechargeService.getLedger(
                RoleGuard.requireUserId(userIdHeader), pageNo, pageSize));
    }
}
