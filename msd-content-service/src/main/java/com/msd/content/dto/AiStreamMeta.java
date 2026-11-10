package com.msd.content.dto;

/**
 * SSE 首帧元信息。
 *
 * @param requestId      本次请求标识
 * @param sessionId      伴看会话标识；RAG 场景为空
 * @param episodeId      伴看当前剧集；RAG 场景为空
 * @param rewrittenQuery RAG 改写后的查询；伴看场景为空
 */
public record AiStreamMeta(
        String requestId,
        String sessionId,
        Long episodeId,
        String rewrittenQuery
) {

    /**
     * 统一空值。
     */
    public AiStreamMeta {
        requestId = requestId == null ? "" : requestId;
        sessionId = sessionId == null ? "" : sessionId;
        rewrittenQuery = rewrittenQuery == null ? "" : rewrittenQuery;
    }
}
