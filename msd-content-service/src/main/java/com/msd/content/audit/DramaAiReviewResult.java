package com.msd.content.audit;

import java.util.List;

/**
 * 写回 t_drama.ai_review_result 的完整审核结果。
 *
 * @param scene         审核场景
 * @param verdict       审核结论；降级时为 null
 * @param degraded      合规审核是否降级
 * @param degradedFlags 合规审核降级标记
 * @param elapsedMs     合规审核耗时
 * @param auditedAt     审核时间
 * @param advisory      Rex 情感/标签建议；旧记录或未启用时为 null
 * @param requestId     审核请求 ID，供审核反馈关联
 */
public record DramaAiReviewResult(
        String scene,
        AuditVerdict verdict,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs,
        String auditedAt,
        DramaReviewAdvisory advisory,
        String requestId
) {

    /**
     * 统一空值，避免写入 null 数组。
     */
    public DramaAiReviewResult {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
        requestId = requestId == null ? "" : requestId;
    }

    /**
     * 兼容旧调用方：不携带 Rex 建议和 requestId。
     */
    public DramaAiReviewResult(String scene, AuditVerdict verdict, boolean degraded,
                               List<String> degradedFlags, long elapsedMs, String auditedAt) {
        this(scene, verdict, degraded, degradedFlags, elapsedMs, auditedAt, null, "");
    }

    /**
     * 兼容旧调用方：携带 Rex 建议但不带 requestId。
     */
    public DramaAiReviewResult(String scene, AuditVerdict verdict, boolean degraded,
                               List<String> degradedFlags, long elapsedMs, String auditedAt,
                               DramaReviewAdvisory advisory) {
        this(scene, verdict, degraded, degradedFlags, elapsedMs, auditedAt, advisory, "");
    }

    /**
     * 替换 advisory，用于 JSON 超限时逐级裁剪。
     */
    public DramaAiReviewResult withAdvisory(DramaReviewAdvisory value) {
        return new DramaAiReviewResult(scene, verdict, degraded, degradedFlags,
                elapsedMs, auditedAt, value, requestId);
    }
}
