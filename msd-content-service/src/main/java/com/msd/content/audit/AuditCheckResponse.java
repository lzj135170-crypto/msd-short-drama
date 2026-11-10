package com.msd.content.audit;

import java.util.List;

/**
 * 结构化审核响应。
 *
 * @param verdict       审核结论；降级时为 null，调用方不得自动判定通过
 * @param degraded      是否发生降级
 * @param degradedFlags 降级标记：audit_timeout、audit_parse_error、audit_unavailable
 * @param elapsedMs     审核耗时（毫秒）
 */
public record AuditCheckResponse(
        AuditVerdict verdict,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs
) {

    /**
     * 统一空值，避免调用方判空。
     */
    public AuditCheckResponse {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
