package com.msd.agent.dto;

/**
 * 远程 audit-agent 的调用结果。
 *
 * @param reply     审核文本
 * @param source    remote / local-fallback
 * @param degraded  是否已降级；true 表示 Supervisor 应回退本地 audit-agent
 * @param agent     A2A Agent 名称
 * @param sessionId 本次调用使用的会话 ID
 */
public record RemoteAuditReply(
        String reply,
        String source,
        boolean degraded,
        String agent,
        String sessionId) {
}
