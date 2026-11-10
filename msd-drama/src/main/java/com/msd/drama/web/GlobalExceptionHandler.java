package com.msd.drama.web;

import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 短剧服务统一异常处理，把业务异常映射为对应 HTTP 状态码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 BizException，保留统一响应体。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException ex) {
        // 1. 按业务错误码选择 HTTP 状态
        HttpStatus status = switch (ex.getCode()) {
            case 40100 -> HttpStatus.UNAUTHORIZED;
            case 40300 -> HttpStatus.FORBIDDEN;
            case 40400 -> HttpStatus.NOT_FOUND;
            case 40900 -> HttpStatus.CONFLICT;
            case 42900 -> HttpStatus.TOO_MANY_REQUESTS;
            case 50000 -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
        // 2. 返回统一 Result，HTTP 状态与业务码对应
        return ResponseEntity.status(status)
                .body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * 处理 Bean Validation 错误，返回第一个字段提示。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        // 1. 取第一个字段错误作为提示
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ResultCode.PARAM_ERROR.getMessage());

        // 2. 返回统一 400
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), message));
    }

    /**
     * 处理请求体无法解析，例如空 body 或非法 JSON。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        // 1. 解析失败统一按参数错误返回
        return ResponseEntity.badRequest()
                .body(Result.fail(ResultCode.PARAM_ERROR.getCode(), "请求体格式不正确"));
    }
}
