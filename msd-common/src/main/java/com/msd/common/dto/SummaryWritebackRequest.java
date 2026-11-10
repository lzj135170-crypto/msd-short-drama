package com.msd.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 分集摘要回写请求。
 *
 * @param summary 生成的分集摘要，1-1000 字
 * @param model   生成摘要使用的模型标识，仅用于日志排查，可为空
 */
public record SummaryWritebackRequest(
        @NotBlank @Size(max = 1000) String summary,
        @Size(max = 64) String model
) {
}
