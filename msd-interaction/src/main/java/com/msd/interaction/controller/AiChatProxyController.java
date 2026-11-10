package com.msd.interaction.controller;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import com.msd.interaction.client.AgentClient;
import com.msd.interaction.dto.AiChatResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动服务到 AI Agent 的代理接口，用于验证完整链路追踪。
 */
@RestController
@RequestMapping("/internal/ai")
public class AiChatProxyController {

    // Agent 服务 Feign 客户端
    private final AgentClient agentClient;

    /**
     * 注入 Agent Feign 客户端。
     */
    public AiChatProxyController(AgentClient agentClient) {
        this.agentClient = agentClient;
    }

    /**
     * 通过 Feign 调用 Agent 对话接口。
     */
    @GetMapping("/chat")
    public Result<AiChatResult> chat(
            @RequestParam(defaultValue = "讲个笑话") String prompt,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE,
                    required = false) String role) {
        // 1. Mock 代理只允许管理员经网关调用；直连 8104 的运维调用可以不带角色
        RoleGuard.requireAdminIfPresent(role);
        // 2. Feign 调用 Agent；feign-micrometer 自动创建 client span 并注入 traceparent
        // 3. Agent 返回的 Result 原样透传
        return agentClient.chat(prompt);
    }
}
