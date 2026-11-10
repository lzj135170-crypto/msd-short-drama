package com.msd.agent.dto;

/**
 * 远程审核演示接口请求。
 *
 * @param content   待审核内容
 * @param sessionId 可选会话 ID，为空时由服务生成
 */
public record RemoteAuditRequest(String content, String sessionId) {
}
