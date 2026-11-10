package com.msd.agent.routing;

/**
 * Supervisor 支持的意图路由。
 */
public enum IntentRoute {

    /** 追剧伴看、当前集剧情和前情。 */
    COMPANION("companion-agent"),
    /** 内容合规审核。 */
    AUDIT("audit-agent"),
    /** 数据分析和榜单解读。 */
    DATA("data-agent"),
    /** 自然语言找剧。 */
    SEARCH("search-agent"),
    /** 根据偏好推荐短剧。 */
    RECOMMEND("search-agent"),
    /** 评论、文案和社媒互动。 */
    SOCIAL("social-agent"),
    /** 无法判断或低置信度，交给 Supervisor 模型兜底。 */
    SUPERVISOR("supervisor");

    // 路由对应的默认子 Agent
    private final String agent;

    /**
     * 绑定默认子 Agent 名称。
     */
    IntentRoute(String agent) {
        this.agent = agent;
    }

    /**
     * 返回默认子 Agent 名称。
     */
    public String agent() {
        return agent;
    }
}
