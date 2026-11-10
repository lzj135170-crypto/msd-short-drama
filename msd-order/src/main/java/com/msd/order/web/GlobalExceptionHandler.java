package com.msd.order.web;

import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 订单服务统一异常处理，把业务异常映射为对应 HTTP 状态码。
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
     * 处理 Feign 调用异常，避免下游 404/宕机时落到 Spring 默认错误体。
     */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Result<Void>> handleFeignException(FeignException ex) {
        // 1. 下游 404 视为资源不存在
        if (ex.status() == 404) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.fail(ResultCode.NOT_FOUND.getCode(), "剧集不存在"));
        }
        // 2. 其他情况按依赖服务不可用处理
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail(ResultCode.SYSTEM_ERROR.getCode(), "依赖服务暂不可用"));
    }
}
