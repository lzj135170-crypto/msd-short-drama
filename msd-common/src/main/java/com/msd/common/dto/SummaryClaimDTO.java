package com.msd.common.dto;

/**
 * 分集摘要生成认领结果。
 *
 * <p>调用方只有在 claimed=true 时才负责生成摘要；否则应使用返回的现有摘要等待重试。</p>
 *
 * @param claimed       是否认领成功，true 表示调用方获得了本次生成权
 * @param summary       当前已有摘要，没有时为空字符串
 * @param summaryStatus 当前摘要状态：0 缺失，1 生成中，2 可用，3 失败
 */
public record SummaryClaimDTO(
        boolean claimed,
        String summary,
        Integer summaryStatus
) {
}
