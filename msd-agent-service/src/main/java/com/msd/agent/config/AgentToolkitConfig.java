package com.msd.agent.config;

import com.msd.agent.tool.CompanionTools;
import com.msd.agent.tool.DramaTools;
import com.msd.agent.tool.RagSearchTools;
import com.msd.agent.tool.RemoteAuditTools;
import com.msd.agent.tool.SpringAiToolBridge;
import com.msd.agent.tool.ToolGuard;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentScope 工具集配置：把 Spring AI @Tool 风格的业务工具注册进 Supervisor 的 Toolkit。
 *
 * <p>audit profile 使用独立的 auditToolkit，不加载 Supervisor 工具集。</p>
 */
@Configuration
@Profile("!audit")
@Conditional(AgentModelConfiguredCondition.class)
public class AgentToolkitConfig {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AgentToolkitConfig.class);

    /**
     * 创建 Supervisor 使用的工具容器。
     *
     * <p>除短剧只读工具外，追加远程 A2A 审核工具；远程不可用时工具内部返回
     * {@code [DEGRADED]}，由 Supervisor 再回退到本地 audit-agent。</p>
     */
    @Bean
    public Toolkit agentToolkit(DramaTools dramaTools,
            CompanionTools companionTools,
            RagSearchTools ragSearchTools,
            ObjectProvider<RemoteAuditTools> remoteAuditToolsProvider,
            ToolGuard toolGuard) {
        // 1. 复用 Spring AI 生成工具定义和调用逻辑，统一挂载沙箱
        List<AgentTool> tools = new ArrayList<>(SpringAiToolBridge.adapt(dramaTools, toolGuard));

        // 2. 注册 AI 伴看工具：当前集摘要和前情检索
        tools.addAll(SpringAiToolBridge.adapt(companionTools, toolGuard));

        // 3. 注册真实 RAG 找剧工具，替换旧 Mock RAG 入口
        tools.addAll(SpringAiToolBridge.adapt(ragSearchTools, toolGuard));

        // 4. audit profile 之外才注册远程审核工具，避免 audit 进程自调用
        RemoteAuditTools remoteAuditTools = remoteAuditToolsProvider.getIfAvailable();
        if (remoteAuditTools != null) {
            tools.addAll(SpringAiToolBridge.adapt(remoteAuditTools, toolGuard));
        }

        // 5. 注册前按 allowlist 过滤；配置了不存在的工具时启动期失败
        tools = toolGuard.filterAndValidate(tools, toolGuard.getAllow());

        // 6. 注册到 AgentScope Toolkit，默认进入 basic 组
        Toolkit toolkit = new Toolkit();
        tools.forEach(toolkit::registerAgentTool);

        // 7. 启动时打印工具名，便于确认注解扫描、沙箱和桥接结果
        log.info("AgentScope tools registered: {}",
                tools.stream().map(AgentTool::getName).toList());
        return toolkit;
    }
}
