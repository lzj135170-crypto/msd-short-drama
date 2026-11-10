package com.msd.drama.dto;

import java.util.List;

/**
 * 首页聚合数据。
 *
 * @param banners    首页轮播位列表
 * @param sections   推荐区块列表
 * @param categories 分类列表
 */
public record HomeFeed(
        List<HomeBanner> banners,
        List<HomeSection> sections,
        List<CategoryView> categories
) {
}
