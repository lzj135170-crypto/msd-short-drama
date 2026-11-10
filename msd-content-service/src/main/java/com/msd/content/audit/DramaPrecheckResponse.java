package com.msd.content.audit;

import java.util.List;

/**
 * 创作者 AI 预检结果。
 *
 * <p>verdict 为 null 表示合规判断降级，调用方不得据此自动通过；
 * 文案建议可以单独降级，不影响合规结论。</p>
 *
 * @param requestId           本次请求 ID，供反馈和日志关联
 * @param verdict             合规结论，降级时为 null
 * @param degraded            合规判断是否降级
 * @param degradedFlags       合规降级标记
 * @param suggestionDegraded  文案建议是否降级
 * @param titleSuggestions    标题建议
 * @param subtitleSuggestions 副标题建议
 * @param tagSuggestions      标签建议
 * @param tips                整改建议
 * @param cached              是否命中缓存
 * @param elapsedMs           本次预检耗时（毫秒）
 */
public record DramaPrecheckResponse(
        String requestId,
        AuditVerdict verdict,
        boolean degraded,
        List<String> degradedFlags,
        boolean suggestionDegraded,
        List<String> titleSuggestions,
        List<String> subtitleSuggestions,
        List<String> tagSuggestions,
        List<String> tips,
        boolean cached,
        long elapsedMs
) {

    /**
     * 统一空值，避免调用方判空。
     */
    public DramaPrecheckResponse {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
        titleSuggestions = titleSuggestions == null ? List.of() : List.copyOf(titleSuggestions);
        subtitleSuggestions = subtitleSuggestions == null
                ? List.of() : List.copyOf(subtitleSuggestions);
        tagSuggestions = tagSuggestions == null ? List.of() : List.copyOf(tagSuggestions);
        tips = tips == null ? List.of() : List.copyOf(tips);
    }

    /**
     * 缓存命中时替换请求 ID 和耗时，保留原预检内容。
     */
    public DramaPrecheckResponse withCacheHit(String newRequestId) {
        return new DramaPrecheckResponse(newRequestId, verdict, degraded, degradedFlags,
                suggestionDegraded, titleSuggestions, subtitleSuggestions, tagSuggestions,
                tips, true, 0);
    }
}
