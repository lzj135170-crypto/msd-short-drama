package com.msd.agent.tool;

import com.msd.agent.config.AgentModelConfiguredCondition;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supervisor 子 Agent 调用上限 Hook。
 *
 * <p>AgentScope 的 agent_spawn 不经过 Spring AI 工具桥接，不能由 ToolGuard 覆盖；
 * 这里按用户 + 会话统计 spawn 次数，超过上限时把工具名替换成不可执行的占位名。</p>
 */
@Component
@Profile("!audit")
@Conditional(AgentModelConfiguredCondition.class)
public class AgentGuardHook implements Hook {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AgentGuardHook.class);
    /** 单个会话允许的最大 spawn 次数。 */
    private final int maxSpawn;
    /** 会话计数器。 */
    private final ConcurrentHashMap<String, AtomicInteger> spawnCounts = new ConcurrentHashMap<>();

    /**
     * 注入 spawn 上限。
     */
    public AgentGuardHook(@Value("${msd.ai.agent.routing.max-spawn:4}") int maxSpawn) {
        this.maxSpawn = Math.max(maxSpawn, 1);
    }

    /**
     * 在工具执行前检查 agent_spawn 次数。
     */
    @Override
    @SuppressWarnings("removal")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreActingEvent preActing)) {
            return Mono.just(event);
        }
        ToolUseBlock toolUse = preActing.getToolUse();
        if (toolUse == null || !"agent_spawn".equals(toolUse.getName())) {
            return Mono.just(event);
        }

        // 1. 计数器按用户 + 会话隔离，并限制 map 规模避免长期运行内存膨胀
        String key = sessionKey(preActing);
        if (spawnCounts.size() > 10_000) {
            spawnCounts.clear();
        }
        int count = spawnCounts.computeIfAbsent(key, ignored -> new AtomicInteger())
                .incrementAndGet();

        // 2. 超过上限时把调用替换成不存在的工具，内置 spawn 不会执行
        if (count > maxSpawn) {
            preActing.setToolUse(ToolUseBlock.builder()
                    .id(toolUse.getId())
                    .name("agent_spawn_blocked")
                    .input(toolUse.getInput())
                    .content("spawn limit exceeded")
                    .build());
            log.warn("agent spawn blocked: key={}, count={}, max={}", key, count, maxSpawn);
        }
        return Mono.just(event);
    }

    /**
     * 用户 + 会话计数键；缺少状态时按进程级分组。
     */
    private String sessionKey(PreActingEvent event) {
        try {
            AgentState state = event.getAgent() == null ? null : event.getAgent().getAgentState();
            if (state == null) {
                return "anonymous:default";
            }
            String userId = state.getUserId() == null ? "anonymous" : state.getUserId();
            String sessionId = state.getSessionId() == null ? "default" : state.getSessionId();
            return userId + ":" + sessionId;
        } catch (RuntimeException ex) {
            log.debug("read agent state failed for spawn count", ex);
            return "anonymous:default";
        }
    }
}
