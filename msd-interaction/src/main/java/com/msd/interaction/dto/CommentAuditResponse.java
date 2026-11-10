package com.msd.interaction.dto;

import java.util.List;

/**
 * content 结构化审核响应。
 *
 * @param verdict       审核结论；降级时为 null
 * @param degraded      是否降级
 * @param degradedFlags 降级标记
 * @param elapsedMs     耗时
 */
public record CommentAuditResponse(
        CommentAuditVerdict verdict,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs
) {

    /**
     * 统一空值。
     */
    public CommentAuditResponse {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
