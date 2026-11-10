package com.msd.agent.controller;

import com.msd.agent.dto.AiReply;
import com.msd.agent.service.AiChatService;
import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AI 对话示例接口，用于验证慢调用熔断。
 */
@RestController
@RequestMapping("/internal/ai")
public class AiChatController {

    // AI 对话服务
    private final AiChatService aiChatService;

    /**
     * 注入 AI 对话服务。
     */
    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    /**
     * 查询一次 AI 回复。
     */
    @GetMapping("/chat")
    public Mono<Result<AiReply>> chat(
            @RequestParam(defaultValue = "讲个笑话") String prompt,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE,
                    required = false) String role) {
        // 1. Mock 接口经网关只允许 ADMIN；直连 8105 的联调可以不带角色
        RoleGuard.requireAdminIfPresent(role);
        // 2. 阻塞调用放到 boundedElastic，避免占用 Netty 事件循环
        // 3. @SentinelResource 在阻塞方法上统计 RT 和异常
        // 4. 统一包装成 Result
        return Mono.fromCallable(() -> aiChatService.chat(prompt))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }
}
