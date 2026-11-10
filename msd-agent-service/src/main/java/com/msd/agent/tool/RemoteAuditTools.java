package com.msd.agent.tool;

import com.msd.agent.dto.RemoteAuditReply;
import com.msd.agent.service.RemoteAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.UUID;

/**
 * Supervisor 的远程审核工具。
 *
 * <p>工具结果带来源标记：{@code [REMOTE]} 表示 A2A 调用成功，
 * {@code [DEGRADED]} 表示远程不可用，Supervisor 应改用本地 audit-agent。</p>
 */
@Component
@Profile("!audit")
public class RemoteAuditTools {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(RemoteAuditTools.class);

    // 远程审核服务，内部负责重试与降级
    private final RemoteAuditService remoteAuditService;
    // 工具级阻塞上限，需短于 Supervisor 整体请求超时
    private final Duration toolTimeout;

    /**
     * 注入远程审核服务，以及工具级阻塞上限。
     *
     * <p>工具超时短于 Supervisor 的整体请求超时，确保远程慢调用也能及时降级，
     * 给本地 audit-agent 留出执行时间。</p>
     */
    public RemoteAuditTools(RemoteAuditService remoteAuditService,
            @Value("${msd.ai.a2a.remote.tool-timeout:90s}") Duration toolTimeout) {
        this.remoteAuditService = remoteAuditService;
        this.toolTimeout = toolTimeout;
    }

    /**
     * 调用远程 A2A audit-agent 审核内容。
     */
    @Tool(name = "audit_remote",
            description = "调用远程 A2A audit-agent 做内容合规审核。"
                    + "返回 [REMOTE] 表示成功；返回 [DEGRADED] 表示远程不可用，需改用本地 audit-agent。")
    public String auditRemote(
            @ToolParam(description = "待审核的标题、简介、剧集信息或评论", required = true)
            String content) {
        // 1. 空内容无需远程调用，直接标记降级
        if (!StringUtils.hasText(content)) {
            return "[DEGRADED] 待审核内容为空，已转本地 audit-agent。";
        }

        String sessionId = "a2a-tool-" + UUID.randomUUID();
        try {
            // 2. 工具运行在 boundedElastic 线程，可安全阻塞等待 A2A 结果；
            // 工具级超时保证远程慢调用不会吃掉 Supervisor 的全部时间预算
            RemoteAuditReply reply = remoteAuditService
                    .audit(content, "system", sessionId)
                    .block(toolTimeout);
            if (reply == null || !StringUtils.hasText(reply.reply())) {
                return "[DEGRADED] 远程 audit-agent 未返回内容，已转本地 audit-agent。";
            }

            // 3. 明确标记来源，供 Supervisor 按 AGENTS.md 决定是否回退
            return (reply.degraded() ? "[DEGRADED] " : "[REMOTE] ") + reply.reply();
        } catch (RuntimeException ex) {
            log.error("Remote audit tool failed: sessionId={}", sessionId, ex);
            return "[DEGRADED] 远程 audit-agent 调用失败，已转本地 audit-agent。";
        }
    }
}
