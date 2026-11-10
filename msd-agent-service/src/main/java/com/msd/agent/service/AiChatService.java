package com.msd.agent.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.msd.agent.client.MockLlmClient;
import com.msd.agent.client.MockRagClient;
import com.msd.agent.dto.AiReply;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 对话服务，Sentinel 资源 aiChat 包住 RAG 检索和大模型调用。
 */
@Service
public class AiChatService {

    /** 限流、熔断或模型异常时返回的兜底话术。 */
    public static final String FALLBACK_REPLY = "当前太火爆，请稍后再试";

    // 模拟大模型客户端
    private final MockLlmClient mockLlmClient;
    // 模拟 RAG 检索客户端
    private final MockRagClient mockRagClient;
    // Micrometer Observation 注册表，用于生成链路 span
    private final ObservationRegistry observationRegistry;

    /**
     * 注入模拟 RAG、模拟大模型和 Observation 注册表。
     */
    public AiChatService(MockLlmClient mockLlmClient,
                         MockRagClient mockRagClient,
                         ObservationRegistry observationRegistry) {
        this.mockLlmClient = mockLlmClient;
        this.mockRagClient = mockRagClient;
        this.observationRegistry = observationRegistry;
    }

    /**
     * 调用 RAG 与模型；慢调用比例超阈值、异常或熔断打开时走降级。
     */
    @SentinelResource(value = "aiChat", blockHandler = "blockHandler", fallback = "fallback")
    public AiReply chat(String prompt) {
        // 1. 创建 ai.chat 父 span
        Observation aiChat = Observation.createNotStarted("ai.chat", observationRegistry)
                .lowCardinalityKeyValue("ai.scene", "drama.chat");
        // 2. observe 会自动 start/openScope/stop，保证子 span 挂到 ai.chat
        return aiChat.observe(() -> {
            // 3. RAG 检索 span，记录 top-k
            List<String> contexts = Observation.createNotStarted("rag.retrieve", observationRegistry)
                    .lowCardinalityKeyValue("rag.top_k", String.valueOf(mockRagClient.getTopK()))
                    .observe(() -> mockRagClient.retrieve(prompt));
            // 4. 模型生成 span，记录模型名和上下文数量
            String reply = Observation.createNotStarted("llm.generate", observationRegistry)
                    .lowCardinalityKeyValue("llm.model", "mock-llm")
                    .lowCardinalityKeyValue("llm.context_count", String.valueOf(contexts.size()))
                    .observe(() -> mockLlmClient.complete(prompt));
            // 5. 父 span 记录命中数，正常返回 degraded=false
            aiChat.lowCardinalityKeyValue("rag.hit_count", String.valueOf(contexts.size()));
            aiChat.lowCardinalityKeyValue("ai.degraded", "false");
            return new AiReply(reply, false);
        });
    }

    /**
     * 熔断打开后快速返回兜底话术。
     */
    public AiReply blockHandler(String prompt, BlockException ex) {
        // 1. 被限流或熔断时直接返回兜底，不再调用模型
        return new AiReply(FALLBACK_REPLY, true);
    }

    /**
     * 模型异常时返回兜底话术。
     */
    public AiReply fallback(String prompt, Throwable ex) {
        // 1. 模型超时/报错时返回兜底，避免异常继续向上冒泡
        return new AiReply(FALLBACK_REPLY, true);
    }
}
