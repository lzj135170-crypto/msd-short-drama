package com.msd.agent.config;

import com.msd.agent.tool.AgentGuardHook;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.util.List;

/**
 * AgentScope Supervisor 配置：只负责 supervisor 主 Agent。
 *
 * <p>audit profile 不创建 Supervisor，避免 8106 进程同时暴露两套 Agent 入口。</p>
 */
@Configuration
@Profile("!audit")
@Conditional(AgentModelConfiguredCondition.class)
public class AgentConfig {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    /**
     * 创建名为 supervisor 的主 Agent。
     *
     * <p>Agent 在调用之间无状态，同一会话由 AgentScope 串行化，因此使用单例；
     * 子 Agent 由 Harness 在运行时按规格文件创建。</p>
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public HarnessAgent supervisor(Model model, Toolkit toolkit, AgentStateStore stateStore,
            AgentGuardHook agentGuardHook,
            @Value("${msd.ai.agent.workspace:.agentscope/workspace}") String workspace) {
        Path workspacePath = Path.of(workspace).toAbsolutePath().normalize();
        log.info("AgentScope workspace: {}", workspacePath);

        // 1. 内置工具 deny 列表作为 disable* 的第二道防线；不动 agent_spawn/agent_send
        ToolsConfig toolsConfig = new ToolsConfig();
        toolsConfig.setStrictAllow(false);
        toolsConfig.setDefaultToolsEnabled(true);
        toolsConfig.setDeny(List.of(
                "read_file", "write_file", "edit_file", "grep_files", "glob_files",
                "list_files", "execute", "web_search", "web_fetch",
                "memory_get", "memory_save", "memory_search",
                "skill_manage", "propose_skill"));

        return HarnessAgent.builder()
                .name("supervisor")
                .agentId("supervisor")
                .sysPrompt("你是「码上短剧」的 Supervisor 调度中枢，按 AGENTS.md 的路由规则派发子 Agent 并汇总结果。")
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .workspace(workspacePath)
                .maxIters(8)
                // 最小权限：保留工作区加载、子 Agent 和业务工具，关闭 shell/web/文件/记忆工具
                .disableShellTool()
                .disableWebTools()
                .disableFilesystemTools()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .skillsEnabled(false)
                .toolsConfig(toolsConfig)
                .hook(agentGuardHook)
                .build();
    }
}
