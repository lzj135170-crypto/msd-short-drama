package com.msd.agent.dto;

/**
 * AgentScope Supervisor 对话请求。
 *
 * @param message   用户消息，1~2000 字
 * @param sessionId 会话 ID；为空时由服务端生成
 * @param episodeId 当前正在观看的剧集 ID；伴看场景传，普通对话可为空
 */
public record AgentChatRequest(String message, String sessionId, Long episodeId) {
}
