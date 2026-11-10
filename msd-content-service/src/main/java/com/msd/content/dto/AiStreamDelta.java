package com.msd.content.dto;

/**
 * SSE 回答增量。
 *
 * @param text 增量文本
 */
public record AiStreamDelta(String text) {

    /**
     * 统一 null。
     */
    public AiStreamDelta {
        text = text == null ? "" : text;
    }
}
