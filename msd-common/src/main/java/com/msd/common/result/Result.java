package com.msd.common.result;

import java.io.Serializable;

/**
 * 统一响应体。
 */
public class Result<T> implements Serializable {

    // 响应码，0 表示成功
    private int code;
    // 响应消息
    private String message;
    // 响应数据
    private T data;

    /**
     * 供 JSON 反序列化使用。
     */
    public Result() {
    }

    /**
     * 全参构造。
     */
    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功响应（带数据）。
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功响应（无数据）。
     */
    public static Result<Void> ok() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /**
     * 失败响应（错误码枚举）。
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /**
     * 失败响应（自定义错误码和消息）。
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 获取响应码。
     */
    public int getCode() {
        return code;
    }

    /**
     * 设置响应码。
     */
    public void setCode(int code) {
        this.code = code;
    }

    /**
     * 获取响应消息。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置响应消息。
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 获取响应数据。
     */
    public T getData() {
        return data;
    }

    /**
     * 设置响应数据。
     */
    public void setData(T data) {
        this.data = data;
    }

    /**
     * 判断是否成功。
     */
    public boolean isSuccess() {
        return code == ResultCode.SUCCESS.getCode();
    }
}
