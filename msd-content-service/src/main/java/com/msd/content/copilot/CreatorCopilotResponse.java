package com.msd.content.copilot;

import java.util.List;
import java.util.UUID;

/**
 * 创作者 Copilot 生成结果。
 *
 * @param requestId    本次请求 ID，供采纳反馈关联
 * @param versions     可人工采纳的文案版本
 * @param tips         合规或改写提醒
 * @param degraded     是否降级
 * @param degradedFlags 降级标记
 * @param cached       是否命中缓存
 * @param elapsedMs    耗时
 */
public record CreatorCopilotResponse(
        String requestId,
        List<CreatorCopilotVersion> versions,
        List<String> tips,
        boolean degraded,
        List<String> degradedFlags,
        boolean cached,
        long elapsedMs
) {

    /**
     * 统一空值并保证 requestId 非空。
     */
    public CreatorCopilotResponse {
        versions = versions == null ? List.of() : List.copyOf(versions);
        tips = tips == null ? List.of() : List.copyOf(tips);
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
    }

    /**
     * 缓存命中时替换请求 ID 和耗时。
     */
    public CreatorCopilotResponse withCacheHit(String newRequestId) {
        return new CreatorCopilotResponse(newRequestId, versions, tips, degraded,
                degradedFlags, true, 0);
    }
}
