package com.msd.order.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.order.dto.UnlockResult;
import com.msd.order.service.UnlockFacade;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 剧集解锁接口。
 */
@RestController
@RequestMapping("/internal/orders")
public class UnlockController {

    // 解锁编排服务
    private final UnlockFacade unlockFacade;

    /**
     * 注入解锁编排服务。
     */
    public UnlockController(UnlockFacade unlockFacade) {
        this.unlockFacade = unlockFacade;
    }

    /**
     * 解锁指定剧集，用户 ID 由网关注入。
     */
    @PostMapping("/episodes/{episodeId}/unlock")
    public Result<UnlockResult> unlock(
            @PathVariable("episodeId") Long episodeId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析网关注入的用户 ID
        Long userId = parseUserId(userIdHeader);
        // 2. 执行解锁编排
        return Result.ok(unlockFacade.unlock(userId, episodeId));
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
