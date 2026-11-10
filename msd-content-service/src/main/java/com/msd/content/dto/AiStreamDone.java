package com.msd.content.dto;

import java.util.List;

/**
 * SSE 结束帧。
 *
 * @param degraded      是否发生降级
 * @param degradedFlags 降级标记
 * @param elapsedMs     本次请求耗时
 * @param locked        当前集是否未解锁
 */
public record AiStreamDone(
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs,
        boolean locked
) {

    /**
     * 统一空列表。
     */
    public AiStreamDone {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
