package com.msd.order.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.order.dto.UnlockResult;
import com.msd.order.service.UnlockFacade;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地测试台用的 Seata 回滚接口，仅在 dev 且显式开启时加载。
 */
@RestController
@Profile("dev")
@ConditionalOnProperty(prefix = "msd.dev.faults", name = "enabled", havingValue = "true")
@RequestMapping("/internal/orders/dev")
public class DevUnlockController {

    // 解锁编排服务，用于触发远程调用后的强制失败
    private final UnlockFacade unlockFacade;

    /**
     * 注入解锁编排服务。
     */
    public DevUnlockController(UnlockFacade unlockFacade) {
        this.unlockFacade = unlockFacade;
    }

    /**
     * 解锁指定剧集，并在远程分支成功后强制失败，用于验证全局回滚。
     */
    @PostMapping("/episodes/{episodeId}/unlock-with-failure")
    public Result<UnlockResult> unlockWithFailure(
            @PathVariable("episodeId") Long episodeId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并调用强制失败入口
        Long userId = parseUserId(userIdHeader);
        return Result.ok(unlockFacade.unlockWithForcedFailure(userId, episodeId));
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
