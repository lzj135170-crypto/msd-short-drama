package com.msd.order.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.order.dto.DramaUnlocks;
import com.msd.order.dto.PointsBalance;
import com.msd.order.dto.UnlockState;
import com.msd.order.dto.UnlockedEpisodeItem;
import com.msd.order.service.OrderQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试台使用的订单只读接口。
 */
@RestController
@RequestMapping("/internal/orders")
public class OrderQueryController {

    // 订单只读查询服务
    private final OrderQueryService orderQueryService;

    /**
     * 注入订单查询服务。
     */
    public OrderQueryController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    /**
     * 查询当前用户积分余额。
     */
    @GetMapping("/points")
    public Result<PointsBalance> points(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并返回余额
        Long userId = parseUserId(userIdHeader);
        return Result.ok(orderQueryService.getPoints(userId));
    }

    /**
     * 查询当前用户对某集的解锁状态。
     */
    @GetMapping("/episodes/{episodeId}/unlock-state")
    public Result<UnlockState> unlockState(
            @PathVariable("episodeId") Long episodeId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并返回解锁状态
        Long userId = parseUserId(userIdHeader);
        return Result.ok(orderQueryService.getUnlockState(userId, episodeId));
    }

    /**
     * 查询当前用户在某部短剧下已解锁的剧集。
     */
    @GetMapping("/dramas/{dramaId}/unlocks")
    public Result<DramaUnlocks> dramaUnlocks(
            @PathVariable("dramaId") Long dramaId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并返回已解锁剧集 ID
        Long userId = parseUserId(userIdHeader);
        return Result.ok(orderQueryService.getDramaUnlocks(userId, dramaId));
    }

    /**
     * 分页查询当前用户已解锁的剧集。
     */
    @GetMapping("/unlocks")
    public Result<PageResult<UnlockedEpisodeItem>> unlocks(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并返回已解锁剧集分页
        Long userId = parseUserId(userIdHeader);
        return Result.ok(orderQueryService.getUnlockedEpisodes(userId, pageNo, pageSize));
    }

    /**
     * 解析 X-User-Id，缺失或非法时按未登录处理。
     */
    private Long parseUserId(String userIdHeader) {
        // 1. 网关未注入用户 ID
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        // 2. 非数字同样视为未登录
        try {
            return Long.parseLong(userIdHeader);
        } catch (NumberFormatException ex) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }
}
