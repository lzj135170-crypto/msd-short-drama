package com.msd.content.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 冷启动偏好选择。
 *
 * @param categoryIds 用户选择的分类 ID，最多 5 个
 */
public record ColdStartPreferenceRequest(
        @NotNull @Size(max = 5) List<@Positive Long> categoryIds
) {
}
