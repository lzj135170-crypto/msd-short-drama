package com.msd.agent.dto;

import java.util.List;

/**
 * AgentScope Supervisor 对话响应。
 *
 * @param reply           Supervisor 汇总后的回复
 * @param sessionId       本次使用的会话 ID，客户端后续复用它实现多轮
 * @param route           本次意图路由：COMPANION/AUDIT/DATA/SEARCH/RECOMMEND/SOCIAL/SUPERVISOR
 * @param routeSource     路由来源：RULE/CACHE/MODEL/FALLBACK
 * @param routeConfidence 路由置信度
 * @param routeLatencyMs  路由决策耗时（毫秒）
 * @param cards           从回复中提取的可点击短剧卡片
 */
public record AgentChatReply(
        String reply,
        String sessionId,
        String route,
        String routeSource,
        double routeConfidence,
        long routeLatencyMs,
        List<AgentDramaCard> cards) {

    /**
     * 统一空值。
     */
    public AgentChatReply {
        cards = cards == null ? List.of() : List.copyOf(cards);
    }

    /**
     * 兼容旧调用方，默认没有卡片。
     */
    public AgentChatReply(String reply, String sessionId, String route, String routeSource,
                          double routeConfidence, long routeLatencyMs) {
        this(reply, sessionId, route, routeSource, routeConfidence, routeLatencyMs, List.of());
    }
}
