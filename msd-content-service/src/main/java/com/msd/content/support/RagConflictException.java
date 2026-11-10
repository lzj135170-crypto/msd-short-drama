package com.msd.content.support;

/**
 * 索引重建并发冲突。
 */
public class RagConflictException extends RuntimeException {

    /**
     * 使用错误信息创建异常。
     */
    public RagConflictException(String message) {
        super(message);
    }
}
