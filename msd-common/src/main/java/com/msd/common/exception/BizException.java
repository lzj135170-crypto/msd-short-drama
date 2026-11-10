package com.msd.common.exception;

import com.msd.common.result.ResultCode;

/**
 * 业务异常。
 */
public class BizException extends RuntimeException {

    // 业务错误码
    private final int code;

    /**
     * 使用错误码枚举构造。
     */
    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /**
     * 使用错误码枚举和自定义消息构造。
     */
    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    /**
     * 使用自定义错误码和消息构造。
     */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 获取错误码。
     */
    public int getCode() {
        return code;
    }
}
