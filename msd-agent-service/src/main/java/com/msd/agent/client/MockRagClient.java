package com.msd.agent.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 模拟 RAG 检索客户端：用可配置延迟模拟向量检索，后续替换为 Milvus。
 */
@RefreshScope
@Component
public class MockRagClient {

    // 模拟检索耗时（毫秒），来自 msd.ai.mock.rag-delay-ms
    private final long delayMs;
    // 每次返回的模拟片段数，来自 msd.ai.mock.rag-top-k
    private final int topK;

    /**
     * 注入模拟延迟和 topK，Nacos 配置变更后热刷新。
     */
    public MockRagClient(@Value("${msd.ai.mock.rag-delay-ms:120}") long delayMs,
                         @Value("${msd.ai.mock.rag-top-k:3}") int topK) {
        this.delayMs = delayMs;
        this.topK = topK;
    }

    /**
     * 模拟一次向量检索，返回固定数量的片段。
     */
    public List<String> retrieve(String prompt) {
        // 1. 按配置延迟模拟检索耗时
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            // 2. 恢复中断标记，转成运行时异常交给 Sentinel fallback
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模拟 RAG 检索被中断", ex);
        }
        // 3. 返回固定数量的模拟片段
        return IntStream.rangeClosed(1, topK)
                .mapToObj(i -> "mock-doc-" + i)
                .toList();
    }

    /**
     * 获取配置的 topK，供追踪标签使用。
     */
    public int getTopK() {
        return topK;
    }
}
