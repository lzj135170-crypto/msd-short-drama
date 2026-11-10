package com.msd.drama.dto;

import java.util.List;

/**
 * 首页推荐区块。
 *
 * @param key   区块标识
 * @param title 区块标题
 * @param items 区块内短剧卡片
 */
public record HomeSection(
        String key,
        String title,
        List<DramaCard> items
) {
}
