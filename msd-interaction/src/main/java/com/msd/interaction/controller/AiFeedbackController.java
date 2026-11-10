package com.msd.interaction.controller;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import com.msd.interaction.dto.AiFeedbackRequest;
import com.msd.interaction.dto.AiFeedbackResult;
import com.msd.interaction.service.AiFeedbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 回答反馈接口，经网关访问 /api/interaction/internal/ai/feedback。
 */
@RestController
@RequestMapping("/internal/ai/feedback")
public class AiFeedbackController {

    // 反馈服务
    private final AiFeedbackService feedbackService;

    /**
     * 注入反馈服务。
     */
    public AiFeedbackController(AiFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * 提交一条 AI 回答反馈。
     */
    @PostMapping
    public Result<AiFeedbackResult> submit(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID,
                    required = false) String userIdHeader,
            @Valid @RequestBody AiFeedbackRequest request) {
        // 1. 只接受登录用户反馈，避免匿名刷数据
        Long userId = RoleGuard.requireUserId(userIdHeader);
        return Result.ok(feedbackService.submit(userId, request));
    }
}
