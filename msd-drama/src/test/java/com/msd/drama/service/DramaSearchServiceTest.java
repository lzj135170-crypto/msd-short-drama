package com.msd.drama.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
import com.msd.drama.dto.CategoryView;
import com.msd.drama.dto.DramaCard;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaCategory;
import com.msd.drama.mapper.DramaCategoryMapper;
import com.msd.drama.mapper.DramaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 短剧搜索服务的参数归一化和结果映射测试。
 */
class DramaSearchServiceTest {

    private DramaMapper dramaMapper;
    private DramaCategoryMapper categoryMapper;
    private DramaSearchService service;

    /**
     * 每个用例重新创建 Mock 和服务实例。
     */
    @BeforeEach
    void setUp() {
        dramaMapper = mock(DramaMapper.class);
        categoryMapper = mock(DramaCategoryMapper.class);
        service = new DramaSearchService(dramaMapper, categoryMapper);
    }

    /**
     * 关键词去除首尾空白，LIKE 通配符按 `/` 转义后传给 Mapper。
     */
    @Test
    void searchesWithTrimmedAndEscapedKeyword() {
        Drama drama = drama(5005L, 2004L);
        when(dramaMapper.countSearchPublic("100/%/_//", null)).thenReturn(1L);
        when(dramaMapper.searchPublic("100/%/_//", null, false, 0L, 12))
                .thenReturn(List.of(drama));
        when(categoryMapper.selectList(any())).thenReturn(List.of(category(2004L, "古装")));

        PageResult<DramaCard> result = service.search("  100%_/  ", null, null, null, null);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getPageNo()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(12);
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).categoryName()).isEqualTo("古装");
        verify(dramaMapper).searchPublic("100/%/_//", null, false, 0L, 12);
    }

    /**
     * 空白关键词按不限制关键词处理，分类条件保留，热度和页大小正确归一化。
     */
    @Test
    void normalizesBlankKeywordAndHotSort() {
        when(dramaMapper.countSearchPublic(null, 2002L)).thenReturn(0L);

        PageResult<DramaCard> result = service.search("   ", 2002L, "HOT", 0, 999);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getPageNo()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(50);
        verify(dramaMapper).countSearchPublic(null, 2002L);
        verify(dramaMapper, never()).searchPublic(any(), any(), anyBoolean(), anyLong(), anyInt());
    }

    /**
     * 后续页按 pageNo 和 pageSize 计算 OFFSET。
     */
    @Test
    void computesOffsetForLaterPage() {
        when(dramaMapper.countSearchPublic("总裁", null)).thenReturn(30L);
        when(dramaMapper.searchPublic("总裁", null, false, 24L, 12)).thenReturn(List.of());
        when(categoryMapper.selectList(any())).thenReturn(List.of());

        PageResult<DramaCard> result = service.search("总裁", null, "latest", 3, 12);

        assertThat(result.getPageNo()).isEqualTo(3);
        assertThat(result.getTotal()).isEqualTo(30);
        assertThat(result.getRecords()).isEmpty();
        verify(dramaMapper).searchPublic("总裁", null, false, 24L, 12);
    }

    /**
     * 不支持的排序方式返回参数错误。
     */
    @Test
    void rejectsInvalidSort() {
        assertThatThrownBy(() -> service.search(null, null, "random", 1, 12))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("latest");
    }

    /**
     * 关键词超过 50 字返回参数错误。
     */
    @Test
    void rejectsTooLongKeyword() {
        String keyword = "剧".repeat(51);

        assertThatThrownBy(() -> service.search(keyword, null, null, 1, 12))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("50");
    }

    /**
     * 分类 ID 非正数返回参数错误。
     */
    @Test
    void rejectsInvalidCategory() {
        assertThatThrownBy(() -> service.search(null, 0L, null, 1, 12))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分类");
    }

    /**
     * 分类列表只做实体到视图的映射。
     */
    @Test
    void listsVisibleCategories() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(2001L, "都市"),
                category(2002L, "甜宠")));

        List<CategoryView> categories = service.listCategories();

        assertThat(categories).extracting(CategoryView::name)
                .containsExactly("都市", "甜宠");
    }

    /**
     * 构造短剧实体。
     */
    private Drama drama(Long id, Long categoryId) {
        Drama drama = new Drama();
        drama.setId(id);
        drama.setTitle("测试短剧");
        drama.setSubtitle("副标题");
        drama.setCoverUrl("cover");
        drama.setCategoryId(categoryId);
        drama.setActors("演员甲,演员乙");
        drama.setEpisodeCount(6);
        drama.setViewCount(100L);
        drama.setLikeCount(10L);
        drama.setFavoriteCount(5L);
        drama.setCommentCount(2L);
        drama.setPublishTime(LocalDateTime.of(2026, 10, 1, 10, 0));
        return drama;
    }

    /**
     * 构造分类实体。
     */
    private DramaCategory category(Long id, String name) {
        DramaCategory category = new DramaCategory();
        category.setId(id);
        category.setName(name);
        category.setSort(1);
        category.setStatus(1);
        return category;
    }
}
