package com.msd.content.memory;

import java.util.List;

/**
 * Redis 中的会话记忆快照。
 *
 * @param summary  更早对话压缩后的历史摘要
 * @param messages 最近保留的对话消息
 */
public record StoredMemory(String summary, List<StoredMessage> messages) {

    /**
     * 统一空值，避免反序列化后出现 null。
     */
    public StoredMemory {
        summary = summary == null ? "" : summary;
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * 空快照。
     */
    public static StoredMemory empty() {
        return new StoredMemory("", List.of());
    }
}
