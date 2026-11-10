package com.msd.drama.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
import com.msd.common.result.ResultCode;
import com.msd.drama.dto.CategoryView;
import com.msd.drama.dto.DramaCard;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaCategory;
import com.msd.drama.mapper.DramaCategoryMapper;
import com.msd.drama.mapper.DramaMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 短剧搜索与分类浏览。
 *
 * <p>Demo 阶段使用 MySQL LIKE 查询已上架短剧，不依赖 ES 索引；
 * 关键词中的 LIKE 通配符会被转义，避免用户输入改变匹配范围。</p>
 */
@Service
public class DramaSearchService {

    /** 默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 12;
    /** 每页最大条数。 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 关键词最大长度。 */
    private static final int MAX_KEYWORD_LENGTH = 50;
    /** 分类可见状态值。 */
    private static final int CATEGORY_VISIBLE = 1;
    /** 按最新上架排序。 */
    private static final String SORT_LATEST = "latest";
    /** 按加权热度排序。 */
    private static final String SORT_HOT = "hot";
    /** LIKE 转义符，与 Mapper SQL 中的 ESCAPE 保持一致。 */
    private static final char LIKE_ESCAPE = '/';

    // 短剧 Mapper
    private final DramaMapper dramaMapper;
    // 分类 Mapper
    private final DramaCategoryMapper categoryMapper;

    /**
     * 注入短剧和分类 Mapper。
     */
    public DramaSearchService(DramaMapper dramaMapper, DramaCategoryMapper categoryMapper) {
        this.dramaMapper = dramaMapper;
        this.categoryMapper = categoryMapper;
    }

    /**
     * 查询启用中的分类，按 sort、id 升序。
     */
    public List<CategoryView> listCategories() {
        return selectVisibleCategories().stream()
                .map(this::toCategoryView)
                .toList();
    }

    /**
     * 搜索已上架短剧，关键词和分类可以同时使用。
     *
     * @param keyword    搜索关键词，空白时忽略
     * @param categoryId 分类 ID，为空时不限分类
     * @param sort       latest（默认）或 hot
     * @param pageNo     页码，小于 1 时按 1 处理
     * @param pageSize   每页条数，默认 12，超过 50 时截断
     * @return 分页短剧卡片
     */
    public PageResult<DramaCard> search(String keyword, Long categoryId, String sort,
                                        Integer pageNo, Integer pageSize) {
        // 1. 归一化并校验查询条件
        String normalizedKeyword = normalizeKeyword(keyword);
        if (categoryId != null && categoryId <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "分类 ID 不合法");
        }
        boolean hot = SORT_HOT.equals(normalizeSort(sort));
        int safePage = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safeSize = pageSize == null
                ? DEFAULT_PAGE_SIZE
                : Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));

        // 2. 先统计总数，没有命中时不再查询列表
        long total = dramaMapper.countSearchPublic(normalizedKeyword, categoryId);
        if (total <= 0) {
            return PageResult.empty(safePage, safeSize);
        }

        // 3. 查询当前页，并用可见分类建立 ID 到名称的映射
        long offset = (long) (safePage - 1) * safeSize;
        List<Drama> dramas = dramaMapper.searchPublic(
                normalizedKeyword, categoryId, hot, offset, safeSize);
        Map<Long, String> categoryNames = selectVisibleCategories().stream()
                .collect(Collectors.toMap(DramaCategory::getId, DramaCategory::getName));

        // 4. 转换为首页复用的短剧卡片
        List<DramaCard> records = dramas.stream()
                .map(drama -> toCard(drama, categoryNames))
                .toList();
        return PageResult.of(safePage, safeSize, total, records);
    }

    /**
     * 查询启用中的分类。
     */
    private List<DramaCategory> selectVisibleCategories() {
        return categoryMapper.selectList(
                new LambdaQueryWrapper<DramaCategory>()
                        .eq(DramaCategory::getStatus, CATEGORY_VISIBLE)
                        .orderByAsc(DramaCategory::getSort)
                        .orderByAsc(DramaCategory::getId));
    }

    /**
     * 去除关键词首尾空白并校验长度。
     *
     * <p>空白关键词返回 null，表示不限制关键词；非空关键词会转义 LIKE 通配符。</p>
     */
    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_KEYWORD_LENGTH) {
            throw new BizException(ResultCode.PARAM_ERROR, "搜索关键词不能超过 50 字");
        }
        return escapeLike(trimmed);
    }

    /**
     * 转义 LIKE 中的转义符、百分号和下划线，避免用户输入被当成通配符。
     */
    private String escapeLike(String keyword) {
        StringBuilder escaped = new StringBuilder(keyword.length() + 8);
        for (int index = 0; index < keyword.length(); index++) {
            char current = keyword.charAt(index);
            if (current == LIKE_ESCAPE || current == '%' || current == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(current);
        }
        return escaped.toString();
    }

    /**
     * 归一化排序参数，只允许 latest 和 hot。
     */
    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return SORT_LATEST;
        }
        String normalized = sort.trim().toLowerCase(Locale.ROOT);
        if (!SORT_LATEST.equals(normalized) && !SORT_HOT.equals(normalized)) {
            throw new BizException(ResultCode.PARAM_ERROR, "排序方式只能为 latest 或 hot");
        }
        return normalized;
    }

    /**
     * 分类实体转分类展示信息。
     */
    private CategoryView toCategoryView(DramaCategory category) {
        return new CategoryView(category.getId(), category.getName(), category.getSort());
    }

    /**
     * 短剧实体转短剧卡片，分类名可能为 null。
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
}
