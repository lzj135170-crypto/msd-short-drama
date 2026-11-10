package com.msd.agent.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 子 Agent spawn 次数上限测试。
 */
class AgentGuardHookTest {

    /**
     * 同一会话最多允许 4 次 spawn，第 5 次被替换成不可执行工具。
     */
    @Test
    void blocksSpawnsAfterLimit() {
        Agent agent = mock(Agent.class);
        AgentState state = mock(AgentState.class);
        when(agent.getAgentState()).thenReturn(state);
        when(state.getUserId()).thenReturn("1003");
        when(state.getSessionId()).thenReturn("session-spawn");
        AgentGuardHook hook = new AgentGuardHook(4);

        for (int index = 1; index <= 4; index += 1) {
            PreActingEvent event = spawnEvent(agent, "spawn-" + index);
            hook.onEvent(event).block();
            assertThat(event.getToolUse().getName()).isEqualTo("agent_spawn");
        }

        PreActingEvent blocked = spawnEvent(agent, "spawn-5");
        hook.onEvent(blocked).block();

        assertThat(blocked.getToolUse().getName()).isEqualTo("agent_spawn_blocked");
    }

    /**
     * 构造一次 agent_spawn 调用事件。
     */
    private PreActingEvent spawnEvent(Agent agent, String id) {
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id(id)
                .name("agent_spawn")
                .input(Map.of("agent_id", "audit-agent"))
                .build();
        return new PreActingEvent(agent, new Toolkit(), toolUse);
    }
}
