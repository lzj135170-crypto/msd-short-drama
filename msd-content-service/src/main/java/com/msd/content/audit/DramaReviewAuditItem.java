package com.msd.content.audit;

import java.util.List;
import java.util.UUID;

/**
 * 单部短剧的 AI 审核结果。
 *
 * @param dramaId       短剧 ID
 * @param aiReviewStatus AI 审核状态：2 通过、3 驳回、4 失败
 * @param updated       是否成功写回 drama
 * @param degradedFlags 降级标记
 * @param requestId     本次审核请求 ID，用于反馈和排查
 */
public record DramaReviewAuditItem(
        Long dramaId,
        Integer aiReviewStatus,
        boolean updated,
        List<String> degradedFlags,
        String requestId
) {

    /**
     * 统一空值，并保证 requestId 始终存在。
     */
    public DramaReviewAuditItem {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
        requestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId;
    }

    /**
     * 兼容旧调用方，自动生成 requestId。
     */
    public DramaReviewAuditItem(Long dramaId, Integer aiReviewStatus, boolean updated,
                                List<String> degradedFlags) {
        this(dramaId, aiReviewStatus, updated, degradedFlags, null);
    }
}
