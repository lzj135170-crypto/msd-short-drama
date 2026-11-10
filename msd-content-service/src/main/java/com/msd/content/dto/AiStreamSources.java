package com.msd.content.dto;

import java.util.List;

/**
 * SSE 引用资料帧。
 *
 * @param items 引用列表，伴看为 CompanionSource，找剧为 RagSource
 */
public record AiStreamSources<T>(List<T> items) {

    /**
     * 统一空列表。
     */
    public AiStreamSources {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
