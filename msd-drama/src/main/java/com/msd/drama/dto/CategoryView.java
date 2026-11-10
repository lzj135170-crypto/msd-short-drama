package com.msd.drama.dto;

/**
 * 分类展示信息。
 *
 * @param id   分类 ID
 * @param name 分类名称
 * @param sort 排序值，越小越靠前
 */
public record CategoryView(
        Long id,
        String name,
        Integer sort
) {
}
