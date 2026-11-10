package com.msd.drama.service;

import com.msd.common.exception.BizException;
import com.msd.drama.cache.CacheEvictor;
import com.msd.drama.dto.CreatorDramaDetail;
import com.msd.drama.dto.DramaWriteRequest;
import com.msd.drama.dto.EpisodeWriteItem;
import com.msd.drama.entity.Drama;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.EpisodeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创作者草稿创建、编辑权限和删除测试。
 */
class DramaWriteServiceTest {

    private final DramaMapper dramaMapper = mock(DramaMapper.class);
    private final EpisodeMapper episodeMapper = mock(EpisodeMapper.class);
    private final DramaReviewService reviewService = mock(DramaReviewService.class);
    private final CacheEvictor cacheEvictor = mock(CacheEvictor.class);

    private final DramaWriteService service = new DramaWriteService(
            dramaMapper, episodeMapper, reviewService, cacheEvictor);

    /**
     * 创建草稿时写入短剧和剧集，作者为当前用户。
     */
    @Test
    void createsDraftWithEpisodes() {
        AtomicReference<Drama> inserted = new AtomicReference<>();
        when(dramaMapper.insert(any(Drama.class))).thenAnswer(invocation -> {
            Drama drama = invocation.getArgument(0);
            drama.setId(5100L);
            inserted.set(drama);
            return 1;
        });
        when(dramaMapper.selectById(5100L)).thenAnswer(invocation -> inserted.get());
        when(episodeMapper.selectByDramaId(5100L)).thenReturn(List.of());

        CreatorDramaDetail detail = service.create(1002L, "CREATOR", request());

        assertThat(detail.drama().dramaId()).isEqualTo(5100L);
        assertThat(detail.drama().authorId()).isEqualTo(1002L);
        assertThat(detail.drama().episodeCount()).isEqualTo(2);
        verify(episodeMapper).upsertEpisode(5100L, 1, "第1集", 120, 0L, 1);
        verify(episodeMapper).upsertEpisode(5100L, 2, "第2集", 130, 30L, 1);
    }

    /**
     * 创作者不能编辑他人短剧。
     */
    @Test
    void rejectsEditingOthersDrama() {
        when(dramaMapper.selectById(5100L)).thenReturn(drama(5100L, 1004L, 0));

        assertThatThrownBy(() -> service.update(5100L, 1002L, "CREATOR", request()))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40300));
        verify(dramaMapper, never()).updateById(any(Drama.class));
    }

    /**
     * 已上架短剧必须先下架才能编辑。
     */
    @Test
    void rejectsEditingPublishedDrama() {
        when(dramaMapper.selectById(5100L)).thenReturn(drama(5100L, 1002L, 2));

        assertThatThrownBy(() -> service.update(5100L, 1002L, "CREATOR", request()))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40900));
    }

    /**
     * 草稿可以软删除并失效缓存。
     */
    @Test
    void deletesDraft() {
        when(dramaMapper.selectById(5100L)).thenReturn(drama(5100L, 1002L, 0));
        when(dramaMapper.softDeleteDraft(5100L)).thenReturn(1);

        assertThat(service.delete(5100L, 1002L, "CREATOR")).isTrue();

        verify(cacheEvictor).evictDetailAfterCommit(5100L);
        verify(cacheEvictor).evictHomeDebounced();
    }

    /**
     * 编辑时未提交的旧剧集下线。
     */
    @Test
    void offlinesMissingEpisodesOnUpdate() {
        Drama draft = drama(5100L, 1002L, 3);
        when(dramaMapper.selectById(5100L)).thenReturn(draft);
        when(dramaMapper.updateById(draft)).thenReturn(1);
        when(episodeMapper.selectByDramaId(5100L)).thenReturn(List.of());
        when(episodeMapper.offlineMissingEpisodes(anyLong(), any())).thenReturn(1);

        service.update(5100L, 1002L, "CREATOR", request());

        verify(episodeMapper).offlineMissingEpisodes(5100L, List.of(1, 2));
        verify(cacheEvictor).evictDetailAfterCommit(5100L);
    }

    /**
     * 构造请求。
     */
    private DramaWriteRequest request() {
        return new DramaWriteRequest(
                "测试短剧", "副标题", "", "简介", 2001L, "演员A,演员B",
                List.of(
                        new EpisodeWriteItem(1, "第1集", 120, 0L, 1),
                        new EpisodeWriteItem(2, "第2集", 130, 30L, 1)));
    }

    /**
     * 构造短剧实体。
     */
    private Drama drama(Long id, Long authorId, Integer reviewStatus) {
        Drama drama = new Drama();
        drama.setId(id);
        drama.setAuthorId(authorId);
        drama.setReviewStatus(reviewStatus);
        drama.setDeleted(0);
        return drama;
    }
}
