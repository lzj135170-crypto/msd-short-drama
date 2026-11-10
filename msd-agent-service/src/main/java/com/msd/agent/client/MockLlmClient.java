package com.msd.agent.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟大模型客户端：用可配置延迟和异常率复现慢调用。
 */
@RefreshScope
@Component
public class MockLlmClient {

    // 模拟调用耗时（毫秒），来自 msd.ai.mock.delay-ms
    private final long delayMs;
    // 模拟异常概率，0 表示不注入失败，来自 msd.ai.mock.error-rate
    private final double errorRate;

    /**
     * 注入模拟延迟和异常率，Nacos 配置变更后热刷新。
     */
    public MockLlmClient(@Value("${msd.ai.mock.delay-ms:50}") long delayMs,
                         @Value("${msd.ai.mock.error-rate:0.0}") double errorRate) {
        this.delayMs = delayMs;
        this.errorRate = errorRate;
    }

    /**
     * 模拟一次大模型补全调用。
     */
    public String complete(String prompt) {
        // 1. 按配置的延迟模拟模型耗时
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            // 2. 恢复中断标记，转成运行时异常交给 Sentinel fallback
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模拟模型调用被中断", ex);
        }
        // 3. 按配置的异常率模拟模型报错
        if (errorRate > 0 && ThreadLocalRandom.current().nextDouble() < errorRate) {
            throw new IllegalStateException("模拟大模型调用失败");
        }
        // 4. 返回固定格式的模拟回复
        return "AI 回复：" + prompt;
    }
}
