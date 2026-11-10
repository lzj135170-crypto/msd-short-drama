package com.msd.content.support;

/**
 * 索引构建失败，例如短剧目录导出异常。
 */
public class RagIndexException extends RuntimeException {

    /**
     * 使用错误信息创建异常。
     */
    public RagIndexException(String message) {
        super(message);
    }

    /**
     * 使用错误信息和原因创建异常。
     */
    public RagIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
