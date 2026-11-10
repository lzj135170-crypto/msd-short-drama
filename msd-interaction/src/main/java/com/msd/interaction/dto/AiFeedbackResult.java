package com.msd.interaction.dto;

/**
 * AI 反馈提交结果。
 *
 * @param id        反馈记录 ID
 * @param duplicate 是否命中幂等键，重复提交为 true
 */
public record AiFeedbackResult(Long id, boolean duplicate) {
}
