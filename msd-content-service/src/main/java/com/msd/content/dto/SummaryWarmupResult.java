package com.msd.content.dto;

import java.util.List;

/**
 * 分集摘要批量预热结果。
 *
 * @param scanned        扫描到的缺失摘要剧集数
 * @param generated      本次成功生成数
 * @param skipped        已有摘要或其他实例生成中而跳过数
 * @param failed         生成失败或不可见数
 * @param degradedFlags  汇总的降级标记
 */
public record SummaryWarmupResult(
        int scanned,
        int generated,
        int skipped,
        int failed,
        List<String> degradedFlags
) {

    /**
     * 反序列化时统一空值，避免调用方判空。
     */
    public SummaryWarmupResult {
        degradedFlags = degradedFlags == null ? List.of() : List.copyOf(degradedFlags);
    }
}
