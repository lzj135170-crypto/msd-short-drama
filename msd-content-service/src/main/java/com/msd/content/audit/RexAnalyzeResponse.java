package com.msd.content.audit;

import java.util.List;

/**
 * sidecar /v1/analyze 响应。
 *
 * @param status       ok / partial / degraded
 * @param degraded     整体是否降级
 * @param degradedFlags 降级标记
 */
public record RexAnalyzeResponse(
        String status,
        String model,
        String revision,
        String device,
        int chunks,
        int failedChunks,
        boolean truncated,
        RexSentiment sentiment,
        List<RexTag> tags,
        boolean degraded,
        List<String> degradedFlags,
        List<String> warnings,
        long elapsedMs
) {

    /**
     * 统一空值。
     */
    public RexAnalyzeResponse {
        tags = tags == null ? List.of() : List.copyOf(tags);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 客户端失败时构造降级响应。
     */
    public static RexAnalyzeResponse degraded(String flag, long elapsedMs) {
        return new RexAnalyzeResponse(
                "degraded", "", "", "cpu", 0, 0, false,
                new RexSentiment("unknown", "", false, 0, List.of()),
                List.of(), true,
                flag == null || flag.isBlank() ? List.of("rex_unavailable") : List.of(flag),
                List.of(), Math.max(elapsedMs, 0));
    }
}
