package com.msd.agent.client;

/**
 * Agent 调用 RAG 推荐的最小请求。
 *
 * @param query 拼接后的自然语言检索需求
 * @param topK  返回条数，1-3
 */
public record RagSearchRequest(String query, Integer topK) {
}
