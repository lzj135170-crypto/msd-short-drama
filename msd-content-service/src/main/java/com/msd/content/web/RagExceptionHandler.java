package com.msd.content.web;

import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.content.support.RagConflictException;
import com.msd.content.support.RagIndexException;
import com.msd.content.support.RagUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

/**
 * RAG 接口统一异常处理。
 */
@RestControllerAdvice
public class RagExceptionHandler {

    // 日志
    private static final Logger log = LoggerFactory.getLogger(RagExceptionHandler.class);

    /**
     * 索引重建并发冲突。
     */
    @ExceptionHandler(RagConflictException.class)
    public ResponseEntity<Result<Void>> handleConflict(RagConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.fail(ResultCode.CONFLICT.getCode(), message(ex, "索引重建冲突")));
    }

    /**
     * Milvus / ES / Rerank 等依赖不可用。
     */
    @ExceptionHandler(RagUnavailableException.class)
    public ResponseEntity<Result<Void>> handleUnavailable(RagUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail(50300, message(ex, "RAG 依赖暂不可用")));
    }

    /**
     * 短剧目录导出或索引构建失败。
     */
    @ExceptionHandler(RagIndexException.class)
    public ResponseEntity<Result<Void>> handleIndex(RagIndexException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.fail(50200, message(ex, "索引构建失败")));
    }

    /**
     * 业务异常，保留统一错误码并映射 HTTP 状态。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case 40100 -> HttpStatus.UNAUTHORIZED;
            case 40300 -> HttpStatus.FORBIDDEN;
            case 40400 -> HttpStatus.NOT_FOUND;
            case 40900 -> HttpStatus.CONFLICT;
            case 42900 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * 请求体字段校验失败。
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Result<Void>> handleValidation(WebExchangeBindException ex) {
        // 1. 取第一个字段校验错误，拼成 "字段: 提示"
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        // 2. 统一返回 400 参数错误
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), message));
    }

    /**
     * 请求体格式错误。
     */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Result<Void>> handleBadRequest(ServerWebInputException ex) {
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), "请求体格式错误"));
    }

    /**
     * 业务参数错误。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), message(ex, "参数错误")));
    }

    /**
     * 未预期异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        // 1. 记录完整异常栈便于排查
        log.error("unhandled content service error", ex);
        // 2. 对外只返回通用系统错误，避免泄露内部细节
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.SYSTEM_ERROR.getCode(), "系统错误"));
    }

    /**
     * 提取异常消息，空消息使用默认文案。
     */
    private String message(Throwable throwable, String fallback) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
