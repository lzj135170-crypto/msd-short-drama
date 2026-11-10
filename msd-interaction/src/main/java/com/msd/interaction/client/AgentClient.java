package com.msd.interaction.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import com.msd.interaction.dto.AiChatResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * AI Agent 服务 Feign 客户端。
 * name 为 Nacos 服务名，LoadBalancer 从实例列表中选一个节点。
 */
@FeignClient(name = ServiceNames.AGENT, path = "/internal/ai", configuration = FeignConfig.class)
public interface AgentClient {

    /**
     * 调用 Agent 对话接口。
     */
    @GetMapping("/chat")
    Result<AiChatResult> chat(@RequestParam("prompt") String prompt);
}
