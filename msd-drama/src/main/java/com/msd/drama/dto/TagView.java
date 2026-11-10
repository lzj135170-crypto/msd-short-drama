package com.msd.drama.dto;

/**
 * 标签展示信息。
 *
 * @param id   标签 ID
 * @param name 标签名称
 */
public record TagView(
        Long id,
        String name
) {
}
