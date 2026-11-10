package com.msd.drama.demo;

/**
 * 下游 AI 回复，与 msd-agent-service 的 AiReply 字段保持一致。
 *
 * @param reply    下游生成的回复文本
 * @param degraded 下游是否降级返回
 */
public record AgentReply(String reply, boolean degraded) {
}
