package com.msd.agent.client.dto;

import java.util.List;

/**
 * Agent 工具使用的短剧只读视图。
 *
 * <p>字段与 msd-drama 的 /internal 接口 JSON 对齐；publishTime 使用字符串，
 * 避免跨服务的 Java Time 序列化差异。</p>
 */
public final class AgentDramaViews {

    /**
     * 工具类禁止实例化。
     */
    private AgentDramaViews() {
    }

    /**
     * 首页聚合数据。
     *
     * @param banners    轮播位列表
     * @param sections   推荐区块列表
     * @param categories 分类列表
     */
    public record HomeFeedView(
            List<HomeBannerView> banners,
            List<HomeSectionView> sections,
            List<CategoryView> categories
    ) {
    }

    /**
     * 首页轮播位。
     *
     * @param dramaId  短剧 ID
     * @param title    标题
     * @param subtitle 副标题
     * @param coverUrl 封面地址
     */
    public record HomeBannerView(
            Long dramaId,
            String title,
            String subtitle,
            String coverUrl
    ) {
    }

    /**
     * 首页推荐区块。
     *
     * @param key   区块标识
     * @param title 区块标题
     * @param items 区块内的短剧卡片
     */
    public record HomeSectionView(
            String key,
            String title,
            List<DramaCardView> items
    ) {
    }

    /**
     * 首页短剧卡片。
     *
     * @param dramaId       短剧 ID
     * @param title         标题
     * @param subtitle      副标题
     * @param coverUrl      封面地址
     * @param categoryId    分类 ID
     * @param categoryName  分类名称
     * @param actors        演员，逗号分隔
     * @param episodeCount  剧集总数
     * @param viewCount     播放量
     * @param likeCount     点赞数
     * @param favoriteCount 收藏数
     * @param commentCount  评论数
     * @param publishTime   发布时间字符串
     */
    public record DramaCardView(
            Long dramaId,
            String title,
            String subtitle,
            String coverUrl,
            Long categoryId,
            String categoryName,
            String actors,
            Integer episodeCount,
            Long viewCount,
            Long likeCount,
            Long favoriteCount,
            Long commentCount,
            String publishTime
    ) {
    }

    /**
     * 短剧详情。
     *
     * @param id           短剧 ID
     * @param title        标题
     * @param subtitle     副标题
     * @param coverUrl     封面地址
     * @param description  简介
     * @param category     分类
     * @param tags         标签列表
     * @param actors       演员列表
     * @param authorId     作者 ID
     * @param episodeCount 剧集总数
     * @param publishTime  发布时间字符串
     * @param counters     计数汇总
     * @param episodes     剧集条目列表
     */
    public record DramaDetailView(
            Long id,
            String title,
            String subtitle,
            String coverUrl,
            String description,
            CategoryView category,
            List<TagView> tags,
            List<String> actors,
            Long authorId,
            Integer episodeCount,
            String publishTime,
            DramaCountersView counters,
            List<EpisodeItemView> episodes
    ) {
    }

    /**
     * 分类展示信息。
     *
     * @param id   分类 ID
     * @param name 分类名称
     * @param sort 排序值
     */
    public record CategoryView(
            Long id,
            String name,
            Integer sort
    ) {
    }

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

    /**
     * 短剧冗余计数。
     *
     * @param viewCount     播放量
     * @param likeCount     点赞数
     * @param favoriteCount 收藏数
     * @param commentCount  评论数
     */
    public record DramaCountersView(
            Long viewCount,
            Long likeCount,
            Long favoriteCount,
            Long commentCount
    ) {
    }

    /**
     * 详情页剧集条目。
     *
     * @param episodeId       剧集 ID
     * @param episodeNo       集号
     * @param title           剧集标题
     * @param coverUrl        封面地址
     * @param durationSeconds 时长（秒）
     * @param pricePoints     解锁所需积分
     * @param free            是否免费
     * @param playCount       播放量
     * @param likeCount       点赞数
     * @param commentCount    评论数
     * @param unlockCount     解锁次数
     */
    public record EpisodeItemView(
            Long episodeId,
            Integer episodeNo,
            String title,
            String coverUrl,
            Integer durationSeconds,
            Long pricePoints,
            boolean free,
            Long playCount,
            Long likeCount,
            Long commentCount,
            Long unlockCount
    ) {
    }

    /**
     * 剧集列表条目。
     *
     * @param episodeId   剧集 ID
     * @param dramaId     所属短剧 ID
     * @param episodeNo   集号
     * @param title       剧集标题
     * @param pricePoints 解锁所需积分
     * @param status      剧集状态
     * @param playCount   播放量
     * @param likeCount   点赞数
     * @param commentCount 评论数
     * @param unlockCount 解锁次数
     */
    public record EpisodeView(
            Long episodeId,
            Long dramaId,
            Integer episodeNo,
            String title,
            Long pricePoints,
            Integer status,
            Long playCount,
            Long likeCount,
            Long commentCount,
            Long unlockCount
    ) {
    }
}
