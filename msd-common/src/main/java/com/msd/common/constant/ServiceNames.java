package com.msd.common.constant;

/**
 * 微服务名，与 Nacos 服务 ID 保持一致。
 */
public final class ServiceNames {

    /**
     * 工具类禁止实例化。
     */
    private ServiceNames() {
    }

    /** 网关。 */
    public static final String GATEWAY = "msd-gateway";

    /** 用户与认证服务。 */
    public static final String USER = "msd-user";

    /** 短剧服务。 */
    public static final String DRAMA = "msd-drama";

    /** 订单与积分服务。 */
    public static final String ORDER = "msd-order";

    /** 互动服务。 */
    public static final String INTERACTION = "msd-interaction";

    /** AI Agent 服务。 */
    public static final String AGENT = "msd-agent-service";

    /** RAG 内容推荐服务。 */
    public static final String CONTENT = "msd-content-service";
}
