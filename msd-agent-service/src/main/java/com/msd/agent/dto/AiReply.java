package com.msd.agent.dto;

/**
 * AI 回复结果。
 *
 * @param reply    回复文本
 * @param degraded 是否走了兜底逻辑
 */
public record AiReply(String reply, boolean degraded) {
}
