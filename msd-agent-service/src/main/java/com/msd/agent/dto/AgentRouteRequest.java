package com.msd.agent.dto;

/**
 * 意图路由调试请求。
 *
 * @param message   用户消息
 * @param sessionId 会话 ID，用于读取会话粘性路由
 */
public record AgentRouteRequest(String message, String sessionId) {
}
