package com.msd.common.constant;

/**
 * 通用常量。
 */
public final class CommonConstants {

    /**
     * 工具类禁止实例化。
     */
    private CommonConstants() {
    }

    /** 用户 ID 请求头。 */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 用户角色请求头。 */
    public static final String HEADER_USER_ROLE = "X-User-Role";

    /** 内部调用令牌请求头。 */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** 链路追踪请求头。 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** API 统一前缀。 */
    public static final String API_PREFIX = "/api";

    /** 内部接口前缀。 */
    public static final String INTERNAL_PATH_PREFIX = "/internal/";

    /** 默认分页大小。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大分页大小。 */
    public static final int MAX_PAGE_SIZE = 100;
}
