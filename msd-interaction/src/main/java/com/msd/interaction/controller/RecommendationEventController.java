package com.msd.interaction.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.interaction.dto.RecommendationEventRequest;
import com.msd.interaction.dto.RecommendationEventResult;
import com.msd.interaction.service.RecommendationAttributionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐点击、播放、完播和负反馈接口，用户 ID 由网关注入。
 */
@RestController
@RequestMapping("/internal/recommendations")
public class RecommendationEventController {

    // 推荐归因服务
    private final RecommendationAttributionService attributionService;

    /**
     * 注入归因服务。
     */
    public RecommendationEventController(RecommendationAttributionService attributionService) {
        this.attributionService = attributionService;
    }

    /**
     * 上报一条推荐行为事件。
     */
    @PostMapping("/events")
    public Result<RecommendationEventResult> event(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @Valid @RequestBody RecommendationEventRequest request) {
        // 1. 解析网关注入的用户 ID
        Long userId = parseUserId(userIdHeader);
        // 2. 幂等写入事件
        return Result.ok(attributionService.recordEvent(userId, request));
    }

    /**
     * 解析用户 ID，缺失或非法时返回 401。
     */
    private Long parseUserId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        try {
            long userId = Long.parseLong(userIdHeader);
            if (userId <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return userId;
        } catch (NumberFormatException ex) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }
}
