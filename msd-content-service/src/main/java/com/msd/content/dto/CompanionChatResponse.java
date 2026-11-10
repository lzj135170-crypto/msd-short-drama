package com.msd.content.dto;

import java.util.List;
import java.util.UUID;

/**
 * AI 伴看聊天响应。
 *
 * @param reply         回复文本
 * @param sessionId     客户端会话标识
 * @param episodeId     当前剧集 ID
 * @param degraded      是否发生降级
 * @param degradedFlags 降级标记，如 summary、context、generation、memory_summary
 * @param elapsedMs     处理耗时（毫秒）
 * @param requestId     本次请求标识，用于反馈和日志关联
 * @param locked        当前集是否因未解锁而不可用
 * @param sources       回答引用的当前集摘要和前情，供前端展开
 */
public record CompanionChatResponse(
        String reply,
        String sessionId,
        Long episodeId,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs,
        String requestId,
        boolean locked,
        List<CompanionSource> sources
) {

    /**
     * 统一空值，避免调用方判空。
     */
    public CompanionChatResponse {
        reply = reply == null ? "" : reply;
        sessionId = sessionId == null ? "" : sessionId;
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
    }

    /**
     * 兼容旧调用方，默认没有引用和锁定。
     */
    public CompanionChatResponse(String reply, String sessionId, Long episodeId,
                                 boolean degraded, List<String> degradedFlags, long elapsedMs) {
        this(reply, sessionId, episodeId, degraded, degradedFlags, elapsedMs, null, false,
                List.of());
    }

    /**
     * 兼容不返回引用的调用方。
     */
    public CompanionChatResponse(String reply, String sessionId, Long episodeId,
                                 boolean degraded, List<String> degradedFlags, long elapsedMs,
                                 String requestId, boolean locked) {
        this(reply, sessionId, episodeId, degraded, degradedFlags, elapsedMs, requestId, locked,
                List.of());
    }
}
