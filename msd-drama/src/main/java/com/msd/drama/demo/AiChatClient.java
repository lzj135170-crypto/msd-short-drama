package com.msd.drama.demo;

import com.msd.common.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 调用 msd-agent-service 的 Mock AI 接口，用于虚拟线程压测。
 *
 * <p>只在 dev 且 demo 开关打开时创建，避免非 dev 环境缺少 RestClient Bean。</p>
 */
@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "msd.cache.demo", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AiChatClient {

    // 响应泛型类型，避免每次调用重复构造
    private static final ParameterizedTypeReference<Result<AgentReply>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    // 带超时配置的 AI 下游客户端
    private final RestClient aiRestClient;

    /**
     * 注入带超时配置的 RestClient。
     */
    public AiChatClient(RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    /**
     * 查询一次 AI 回复；下游为空时按失败处理。
     */
    public AgentReply chat(String prompt) {
        // 1. 走 GET /internal/ai/chat，query 参数直接拼到 URI
        Result<AgentReply> result = aiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/ai/chat")
                        .queryParam("prompt", prompt)
                        .build())
                .retrieve()
                .body(RESPONSE_TYPE);

        // 2. 下游返回空或 data 为空时抛出，由压测聚合层统计为失败
        if (result == null || result.getData() == null) {
            throw new IllegalStateException("AI 下游返回为空");
        }
        return result.getData();
    }
}
