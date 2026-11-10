package com.msd.common.result;

/**
 * 统一业务错误码。
 */
public enum ResultCode {

    /** 成功。 */
    SUCCESS(0, "成功"),
    /** 参数错误。 */
    PARAM_ERROR(40000, "参数错误"),
    /** 未登录。 */
    UNAUTHORIZED(40100, "未登录"),
    /** 无权限。 */
    FORBIDDEN(40300, "无权限"),
    /** 资源不存在。 */
    NOT_FOUND(40400, "资源不存在"),
    /** 资源冲突。 */
    CONFLICT(40900, "资源冲突"),
    /** 请求过于频繁。 */
    TOO_MANY_REQUESTS(42900, "请求过于频繁"),
    /** 系统错误。 */
    SYSTEM_ERROR(50000, "系统错误");

    // 错误码
    private final int code;
    // 错误信息
    private final String message;

    /**
     * 使用错误码和错误信息构造枚举项。
     */
    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 获取错误码。
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取错误信息。
     */
    public String getMessage() {
        return message;
    }
}
