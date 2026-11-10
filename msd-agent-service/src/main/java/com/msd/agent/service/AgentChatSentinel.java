package com.msd.agent.service;

import com.alibaba.csp.sentinel.AsyncEntry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.stereotype.Component;

/**
 * Supervisor 对话的 Sentinel 入口。
 *
 * <p>使用 asyncEntry 覆盖整个 Mono 生命周期，限流/熔断规则可以直接作用于 LLM 调用。</p>
 */
@Component
public class AgentChatSentinel {

    /** Sentinel 资源名，与 sentinel-agent-degrade.json 保持一致。 */
    public static final String RESOURCE_NAME = "agentChat";

    /**
     * 进入 agentChat 资源；被限流或熔断时抛出 BlockException。
     */
    public AsyncEntry enter() throws BlockException {
        return SphU.asyncEntry(RESOURCE_NAME);
    }
}
