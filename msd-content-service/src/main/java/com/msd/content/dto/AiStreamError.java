package com.msd.content.dto;

/**
 * SSE 错误帧。
 *
 * @param code    错误码，如 generation、timeout
 * @param message 可展示的错误说明
 */
public record AiStreamError(String code, String message) {

    /**
     * 统一空值。
     */
    public AiStreamError {
        code = code == null ? "" : code;
        message = message == null ? "" : message;
    }
}
