package com.msd.interaction.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.interaction.service.EventStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动事件状态查询，供前端异步确认计数是否已投递和消费。
 */
@RestController
@RequestMapping("/internal/interactions/events")
public class InteractionEventController {

    // 事件状态服务
    private final EventStatusService eventStatusService;

    /**
     * 注入事件状态服务。
     */
    public InteractionEventController(EventStatusService eventStatusService) {
        this.eventStatusService = eventStatusService;
    }

    /**
     * 查询当前用户的事件状态。
     */
    @GetMapping("/{eventId}")
    public Result<EventStatusService.EventStatus> status(
            @PathVariable("eventId") String eventId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并校验事件归属
        return Result.ok(eventStatusService.get(parseUserId(userIdHeader), eventId));
    }

    /**
     * 解析 X-User-Id，缺失或非法时按未登录处理。
     */
    private Long parseUserId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        try {
            return Long.parseLong(userIdHeader);
        } catch (NumberFormatException ex) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }
}
