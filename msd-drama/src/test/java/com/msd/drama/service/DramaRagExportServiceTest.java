package com.msd.drama.service;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.common.result.PageResult;
import com.msd.drama.dto.DramaEpisodeTitleRow;
import com.msd.drama.dto.DramaTagNameRow;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaCategory;
import com.msd.drama.mapper.DramaCategoryMapper;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.DramaTagMapper;
import com.msd.drama.mapper.EpisodeMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG 目录导出服务测试。
 */
class DramaRagExportServiceTest {

    private final DramaMapper dramaMapper = mock(DramaMapper.class);
    private final DramaCategoryMapper categoryMapper = mock(DramaCategoryMapper.class);
    private final DramaTagMapper tagMapper = mock(DramaTagMapper.class);
    private final EpisodeMapper episodeMapper = mock(EpisodeMapper.class);

    private final DramaRagExportService service = new DramaRagExportService(
            dramaMapper, categoryMapper, tagMapper, episodeMapper);

    /**
     * 分类、标签、演员和剧集标题应一次性批量补齐。
     */
    @Test
    void exportsGroupedCatalogFields() {
        Drama drama = drama();
        when(dramaMapper.countRagExport()).thenReturn(1L);
        when(dramaMapper.selectRagExport(0, 200)).thenReturn(List.of(drama));
        when(categoryMapper.selectList(any())).thenReturn(List.of(category()));
        when(tagMapper.selectVisibleTagNamesByDramaIds(List.of(5005L)))
                .thenReturn(List.of(tag("悬疑"), tag("破案")));
        when(episodeMapper.selectOnlineEpisodeTitlesByDramaIds(List.of(5005L)))
                .thenReturn(List.of(
                        episode(6401L, 1, "验尸", "雨夜发现女尸，沈昭被召入大理寺验尸。"),
                        episode(6402L, 2, "追凶", "沈昭追查香料来源，线索指向城西当铺。"),
                        episode(6403L, 3, "第3集 结案", "沈昭当众指认真凶。")));

        PageResult<DramaRagExportDTO> result = service.export(1, 200);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRecords()).hasSize(1);

        DramaRagExportDTO dto = result.getRecords().get(0);
        assertThat(dto.title()).isEqualTo("长安第一女仵作");
        assertThat(dto.categoryName()).isEqualTo("古装悬疑");
        assertThat(dto.tags()).containsExactly("悬疑", "破案");
        assertThat(dto.actors()).containsExactly("林一", "苏晚");
        assertThat(dto.episodeTitles()).containsExactly("第1集 验尸", "第2集 追凶", "第3集 结案");
        assertThat(dto.episodes()).extracting(
                        EpisodeRagExportDTO::id,
                        EpisodeRagExportDTO::episodeNo,
                        EpisodeRagExportDTO::title,
                        EpisodeRagExportDTO::summary)
                .containsExactly(
                        tuple(6401L, 1, "验尸", "雨夜发现女尸，沈昭被召入大理寺验尸。"),
                        tuple(6402L, 2, "追凶", "沈昭追查香料来源，线索指向城西当铺。"),
                        tuple(6403L, 3, "第3集 结案", "沈昭当众指认真凶。"));
        assertThat(dto.publishTime()).isEqualTo("2026-01-01T10:00:00");
    }

    /**
     * 前情提要只透传当前集及之前的摘要和状态。
     */
    @Test
    void recapsCurrentAndPreviousEpisodes() {
        DramaEpisodeTitleRow first = episode(6401L, 1, "验尸", "第一集摘要");
        first.setPricePoints(0);
        first.setSummaryStatus(2);
        DramaEpisodeTitleRow second = episode(6402L, 2, "追凶", "第二集摘要");
        second.setPricePoints(30);
        second.setSummaryStatus(2);
        when(episodeMapper.selectRecapEpisodes(5005L, 2)).thenReturn(List.of(first, second));

        List<com.msd.common.dto.RecapEpisodeDTO> result = service.recap(5005L, 2);

        assertThat(result).extracting(
                        com.msd.common.dto.RecapEpisodeDTO::episodeNo,
                        com.msd.common.dto.RecapEpisodeDTO::summary,
                        com.msd.common.dto.RecapEpisodeDTO::pricePoints,
                        com.msd.common.dto.RecapEpisodeDTO::summaryStatus)
                .containsExactly(
                        tuple(1, "第一集摘要", 0, 2),
                        tuple(2, "第二集摘要", 30, 2));
    }

    /**
     * 规则兜底先按同分类和共同标签重排，再截断。
     */
    @Test
    void relatedFallbackRanksCommonTagsBeforeHeat() {
        Drama source = drama();
        Drama hotOther = drama(5007L, 4L);
        Drama sameCategory = drama(5006L, 3L);
        Drama sameTags = drama(5008L, 4L);
        when(dramaMapper.selectPublicById(5005L)).thenReturn(source);
        when(dramaMapper.selectRelatedCandidates(5005L, 24))
                .thenReturn(List.of(hotOther, sameCategory, sameTags));
        when(categoryMapper.selectList(any())).thenReturn(List.of(category(), category(4L, "现代甜宠")));
        when(tagMapper.selectVisibleTagNamesByDramaIds(List.of(5005L)))
                .thenReturn(List.of(tag("悬疑"), tag("破案")));
        when(tagMapper.selectVisibleTagNamesByDramaIds(List.of(5007L, 5006L, 5008L)))
                .thenReturn(List.of(
                        tag(5007L, "古装"),
                        tag(5006L, "悬疑"),
                        tag(5008L, "悬疑"),
                        tag(5008L, "破案")));
        when(episodeMapper.selectOnlineEpisodeTitlesByDramaIds(any())).thenReturn(List.of());

        List<DramaRagExportDTO> result = service.relatedFallback(5005L, 6);

        assertThat(result).extracting(DramaRagExportDTO::id)
                .containsExactly(5006L, 5008L, 5007L);
    }

    /**
     * 空目录直接返回空分页，不查分类和标签。
     */
    @Test
    void returnsEmptyPageWhenCatalogIsEmpty() {
        when(dramaMapper.countRagExport()).thenReturn(0L);

        PageResult<DramaRagExportDTO> result = service.export(1, 200);

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
    }

    /**
     * 构造短剧实体。
     */
    private Drama drama() {
        return drama(5005L, 3L);
    }

    /**
     * 构造指定 ID 和分类的短剧实体。
     */
    private Drama drama(Long id, Long categoryId) {
        Drama drama = new Drama();
        drama.setId(id);
        drama.setTitle("长安第一女仵作");
        drama.setSubtitle("验尸破案，顺便谈个恋爱");
        drama.setCoverUrl("http://127.0.0.1:9000/msd/covers/" + id + ".jpg");
        drama.setDescription("长安城接连出现离奇命案。");
        drama.setCategoryId(categoryId);
        drama.setActors("林一，苏晚");
        drama.setEpisodeCount(2);
        drama.setPublishTime(LocalDateTime.of(2026, 1, 1, 10, 0, 0));
        drama.setViewCount(1000L);
        drama.setLikeCount(100L);
        drama.setFavoriteCount(50L);
        drama.setCommentCount(20L);
        return drama;
    }

    /**
     * 构造分类实体。
     */
    private DramaCategory category() {
        return category(3L, "古装悬疑");
    }

    /**
     * 构造指定 ID 和名称的分类实体。
     */
    private DramaCategory category(Long id, String name) {
        DramaCategory category = new DramaCategory();
        category.setId(id);
        category.setName(name);
        return category;
    }

    /**
     * 构造标签查询行。
     */
    private DramaTagNameRow tag(String name) {
        return tag(5005L, name);
    }

    /**
     * 构造指定短剧的标签查询行。
     */
    private DramaTagNameRow tag(Long dramaId, String name) {
        DramaTagNameRow row = new DramaTagNameRow();
        row.setDramaId(dramaId);
        row.setName(name);
        return row;
    }

    /**
     * 构造剧集查询行。
     */
    private DramaEpisodeTitleRow episode(Long episodeId, int episodeNo, String title, String summary) {
        DramaEpisodeTitleRow row = new DramaEpisodeTitleRow();
        row.setEpisodeId(episodeId);
        row.setDramaId(5005L);
        row.setEpisodeNo(episodeNo);
        row.setTitle(title);
        row.setSummary(summary);
        return row;
    }
}
