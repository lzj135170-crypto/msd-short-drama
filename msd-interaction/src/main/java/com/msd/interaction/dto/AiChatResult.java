package com.msd.interaction.dto;

/**
 * Agent 的 AI 回复结果，字段与 Agent 的 AiReply 保持一致。
 *
 * @param reply    Agent 返回的回复文本
 * @param degraded 是否降级回复，Agent 不可用时由调用方兜底
 */
public record AiChatResult(String reply, boolean degraded) {
}
