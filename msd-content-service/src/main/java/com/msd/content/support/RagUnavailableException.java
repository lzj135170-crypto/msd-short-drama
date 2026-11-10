package com.msd.content.support;

/**
 * RAG 依赖（Milvus、Elasticsearch、Rerank 等）不可用。
 */
public class RagUnavailableException extends RuntimeException {

    /**
     * 使用错误信息创建异常。
     */
    public RagUnavailableException(String message) {
        super(message);
    }

    /**
     * 使用错误信息和原因创建异常。
     */
    public RagUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
