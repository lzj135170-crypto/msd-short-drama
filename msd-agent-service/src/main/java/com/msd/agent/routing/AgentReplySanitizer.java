package com.msd.agent.routing;

import org.springframework.stereotype.Component;

/**
 * Agent 回复清洗，避免服务端内部标记泄露给最终用户。
 */
@Component
public class AgentReplySanitizer {

    /**
     * 移除 ROUTE_HINT、当前剧集 ID、DEGRADED 等内部标记。
     */
    public String sanitize(String reply) {
        return PromptGuard.stripInternalMarkers(reply);
    }
}
