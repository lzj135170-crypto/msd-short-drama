package com.msd.content.audit;

import java.util.List;

/**
 * 批量短剧 AI 审核结果。
 *
 * @param scanned       扫描的候选数
 * @param audited       成功写回的数量
 * @param passed        写回为通过的数量
 * @param rejected      写回为驳回的数量
 * @param failed        写回为失败的数量
 * @param skipped       状态已变化或短剧不可见的数量
 * @param degradedFlags 汇总的降级标记
 * @param items         每条审核结果
 */
public record DramaReviewAuditBatchResult(
        int scanned,
        int audited,
        int passed,
        int rejected,
        int failed,
        int skipped,
        List<String> degradedFlags,
        List<DramaReviewAuditItem> items
) {

    /**
     * 统一空值。
     */
    public DramaReviewAuditBatchResult {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
