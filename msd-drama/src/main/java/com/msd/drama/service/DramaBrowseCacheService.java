package com.msd.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.msd.drama.cache.MultiLevelCache;
import com.msd.drama.dto.CategoryView;
import com.msd.drama.dto.DramaCard;
import com.msd.drama.dto.DramaCounters;
import com.msd.drama.dto.DramaDetail;
import com.msd.drama.dto.EpisodeItem;
import com.msd.drama.dto.HomeBanner;
import com.msd.drama.dto.HomeFeed;
import com.msd.drama.dto.HomeSection;
import com.msd.drama.dto.TagView;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaCategory;
import com.msd.drama.entity.DramaEpisode;
import com.msd.drama.mapper.DramaCategoryMapper;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.DramaTagMapper;
import com.msd.drama.mapper.EpisodeMapper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 首页与详情的缓存加载器。
 *
 * <p>独立成 Bean，确保 @MultiLevelCache 切面通过 Spring 代理生效。</p>
 */
@Service
public class DramaBrowseCacheService {

    /** 轮播位最大条数 */
    private static final int BANNER_LIMIT = 3;
    /** 每个区块最大条数 */
    private static final int SECTION_LIMIT = 6;
    /** 分类可见状态值 */
    private static final int CATEGORY_VISIBLE = 1;

    // 短剧 Mapper
    private final DramaMapper dramaMapper;
    // 剧集 Mapper
    private final EpisodeMapper episodeMapper;
    // 分类 Mapper
    private final DramaCategoryMapper categoryMapper;
    // 标签 Mapper
    private final DramaTagMapper tagMapper;

    /**
     * 注入短剧、剧集、分类和标签 Mapper。
     */
    public DramaBrowseCacheService(DramaMapper dramaMapper,
                                   EpisodeMapper episodeMapper,
                                   DramaCategoryMapper categoryMapper,
                                   DramaTagMapper tagMapper) {
        this.dramaMapper = dramaMapper;
        this.episodeMapper = episodeMapper;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
    }

    /**
     * 加载首页聚合数据；无参缓存 key 固定为 v1。
     */
    @MultiLevelCache(cacheName = "home", key = "'v1'", policy = "home", lock = true)
    public HomeFeed loadHome() {
        // 1. 查询热播和最新两个区块的数据
        List<Drama> hot = dramaMapper.selectHot(SECTION_LIMIT);
        List<Drama> latest = dramaMapper.selectLatest(SECTION_LIMIT);

        // 2. 查询可见分类，并建立分类 ID 到名称的映射
        List<DramaCategory> categories = categoryMapper.selectList(
                new LambdaQueryWrapper<DramaCategory>()
                        .eq(DramaCategory::getStatus, CATEGORY_VISIBLE)
                        .orderByAsc(DramaCategory::getSort)
                        .orderByAsc(DramaCategory::getId));
        Map<Long, String> categoryNames = categories.stream()
                .collect(Collectors.toMap(DramaCategory::getId, DramaCategory::getName));

        // 3. 轮播位取热播前三条
        List<HomeBanner> banners = hot.stream()
                .limit(BANNER_LIMIT)
                .map(this::toBanner)
                .toList();

        // 4. 固定返回热播和最新两个区块
        List<HomeSection> sections = List.of(
                new HomeSection("hot", "热播推荐",
                        hot.stream().map(drama -> toCard(drama, categoryNames)).toList()),
                new HomeSection("new", "最新上架",
                        latest.stream().map(drama -> toCard(drama, categoryNames)).toList())
        );

        // 5. 分类导航按 sort 升序
        List<CategoryView> categoryViews = categories.stream()
                .map(this::toCategoryView)
                .toList();

        return new HomeFeed(banners, sections, categoryViews);
    }

    /**
     * 加载已上架短剧详情；不存在返回 null，由上层转换为 404。
     */
    @MultiLevelCache(cacheName = "detail", key = "#dramaId", policy = "detail",
            bloomId = "#dramaId", lock = true, cacheNull = true)
    public DramaDetail loadDetail(Long dramaId) {
        // 1. 只允许查看已上架短剧，未上架或已删除返回 null
        Drama drama = dramaMapper.selectPublicById(dramaId);
        if (drama == null) {
            return null;
        }

        // 2. 查询分类、可见标签和在线剧集
        DramaCategory category = drama.getCategoryId() == null
                ? null
                : categoryMapper.selectById(drama.getCategoryId());
        List<TagView> tags = tagMapper.selectVisibleByDramaId(dramaId).stream()
                .map(tag -> new TagView(tag.getId(), tag.getName()))
                .toList();
        List<EpisodeItem> episodes = episodeMapper.selectOnlineByDramaId(dramaId).stream()
                .map(this::toEpisodeItem)
                .toList();

        // 3. 组装详情；响应中不包含视频地址
        return new DramaDetail(
                drama.getId(),
                drama.getTitle(),
                drama.getSubtitle(),
                drama.getCoverUrl(),
                drama.getDescription(),
                category == null ? null : toCategoryView(category),
                tags,
                splitActors(drama.getActors()),
                drama.getAuthorId(),
                drama.getEpisodeCount(),
                drama.getPublishTime(),
                new DramaCounters(drama.getViewCount(), drama.getLikeCount(),
                        drama.getFavoriteCount(), drama.getCommentCount()),
                episodes
        );
    }

    /**
     * 短剧实体转首页轮播项。
     */
    private HomeBanner toBanner(Drama drama) {
        return new HomeBanner(
                drama.getId(),
                drama.getTitle(),
                drama.getSubtitle(),
                drama.getCoverUrl()
        );
    }

    /**
     * 短剧实体转首页卡片，分类名可能为 null。
     */
    private DramaCard toCard(Drama drama, Map<Long, String> categoryNames) {
        return new DramaCard(
                drama.getId(),
                drama.getTitle(),
                drama.getSubtitle(),
                drama.getCoverUrl(),
                drama.getCategoryId(),
                categoryNames.get(drama.getCategoryId()),
                drama.getActors(),
                drama.getEpisodeCount(),
                drama.getViewCount(),
                drama.getLikeCount(),
                drama.getFavoriteCount(),
                drama.getCommentCount(),
                drama.getPublishTime()
        );
    }

    /**
     * 分类实体转首页导航项。
     */
    private CategoryView toCategoryView(DramaCategory category) {
        return new CategoryView(category.getId(), category.getName(), category.getSort());
    }

    /**
     * 剧集实体转详情剧集项；价格为 null 时按免费处理。
     */
    private EpisodeItem toEpisodeItem(DramaEpisode episode) {
        long pricePoints = episode.getPricePoints() == null ? 0L : episode.getPricePoints();
        return new EpisodeItem(
                episode.getId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                episode.getCoverUrl(),
                episode.getDurationSeconds(),
                pricePoints,
                pricePoints == 0L,
                episode.getPlayCount(),
                episode.getLikeCount(),
                episode.getCommentCount(),
                episode.getUnlockCount()
        );
    }

    /**
     * 按中英文逗号或顿号拆分演员字符串，去空白并过滤空项。
     */
    private List<String> splitActors(String actors) {
        if (actors == null || actors.isBlank()) {
            return List.of();
        }
        return Arrays.stream(actors.split("[,，、]"))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }
}
