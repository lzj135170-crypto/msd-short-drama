package com.msd.interaction.dto;

import java.util.List;

/**
 * 写入 t_comment.ai_review_result 的 JSON。
 *
 * @param scene         审核场景
 * @param verdict       审核结论；降级时为 null
 * @param degraded      是否降级
 * @param degradedFlags 降级标记
 * @param elapsedMs     耗时
 * @param auditedAt     审核时间
 */
public record CommentAiReviewPayload(
        String scene,
        CommentAuditVerdict verdict,
        boolean degraded,
        List<String> degradedFlags,
        long elapsedMs,
        String auditedAt
) {

    /**
     * 统一空值。
     */
    public CommentAiReviewPayload {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
