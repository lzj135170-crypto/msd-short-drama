package com.msd.drama.service;

import com.msd.drama.dto.DramaDetail;
import com.msd.drama.dto.HomeFeed;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaCategory;
import com.msd.drama.entity.DramaEpisode;
import com.msd.drama.entity.DramaTag;
import com.msd.drama.mapper.DramaCategoryMapper;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.DramaTagMapper;
import com.msd.drama.mapper.EpisodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DramaBrowseCacheServiceTest {

    private DramaMapper dramaMapper;
    private EpisodeMapper episodeMapper;
    private DramaCategoryMapper categoryMapper;
    private DramaTagMapper tagMapper;
    private DramaBrowseCacheService service;

    @BeforeEach
    void setUp() {
        dramaMapper = mock(DramaMapper.class);
        episodeMapper = mock(EpisodeMapper.class);
        categoryMapper = mock(DramaCategoryMapper.class);
        tagMapper = mock(DramaTagMapper.class);
        service = new DramaBrowseCacheService(dramaMapper, episodeMapper, categoryMapper, tagMapper);
    }

    @Test
    void loadsHomeFeed() {
        Drama drama = drama(5005L, 1L);
        DramaCategory category = category(1L, "古装");
        when(dramaMapper.selectHot(6)).thenReturn(List.of(drama));
        when(dramaMapper.selectLatest(6)).thenReturn(List.of(drama));
        when(categoryMapper.selectList(any())).thenReturn(List.of(category));

        HomeFeed feed = service.loadHome();

        assertThat(feed.banners()).hasSize(1);
        assertThat(feed.banners().get(0).dramaId()).isEqualTo(5005L);
        assertThat(feed.sections()).extracting("key").containsExactly("hot", "new");
        assertThat(feed.sections().get(0).items().get(0).categoryName()).isEqualTo("古装");
        assertThat(feed.categories()).hasSize(1);
    }

    @Test
    void loadsDetail() {
        Drama drama = drama(5005L, 1L);
        DramaCategory category = category(1L, "古装");
        DramaTag tag = new DramaTag();
        tag.setId(9L);
        tag.setName("悬疑");
        DramaEpisode episode = new DramaEpisode();
        episode.setId(100L);
        episode.setEpisodeNo(1);
        episode.setTitle("第1集");
        episode.setCoverUrl("cover");
        episode.setDurationSeconds(120);
        episode.setPricePoints(0L);
        episode.setPlayCount(10L);
        episode.setLikeCount(20L);
        episode.setCommentCount(30L);
        episode.setUnlockCount(40L);

        when(dramaMapper.selectPublicById(5005L)).thenReturn(drama);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(tagMapper.selectVisibleByDramaId(5005L)).thenReturn(List.of(tag));
        when(episodeMapper.selectOnlineByDramaId(5005L)).thenReturn(List.of(episode));

        DramaDetail detail = service.loadDetail(5005L);

        assertThat(detail).isNotNull();
        assertThat(detail.id()).isEqualTo(5005L);
        assertThat(detail.category().name()).isEqualTo("古装");
        assertThat(detail.tags()).extracting("name").containsExactly("悬疑");
        assertThat(detail.episodes()).hasSize(1);
        assertThat(detail.episodes().get(0).free()).isTrue();
    }

    @Test
    void returnsNullWhenDetailMissing() {
        when(dramaMapper.selectPublicById(999L)).thenReturn(null);

        assertThat(service.loadDetail(999L)).isNull();
    }

    private Drama drama(Long id, Long categoryId) {
        Drama drama = new Drama();
        drama.setId(id);
        drama.setTitle("长安第一女仵作");
        drama.setSubtitle("验尸破案");
        drama.setCoverUrl("cover");
        drama.setDescription("description");
        drama.setCategoryId(categoryId);
        drama.setAuthorId(1L);
        drama.setActors("沈知意,裴行舟");
        drama.setEpisodeCount(12);
        drama.setPublishTime(LocalDateTime.of(2026, 10, 25, 12, 30));
        drama.setViewCount(10L);
        drama.setLikeCount(20L);
        drama.setFavoriteCount(30L);
        drama.setCommentCount(40L);
        return drama;
    }

    private DramaCategory category(Long id, String name) {
        DramaCategory category = new DramaCategory();
        category.setId(id);
        category.setName(name);
        category.setSort(1);
        category.setStatus(1);
        return category;
    }
}
