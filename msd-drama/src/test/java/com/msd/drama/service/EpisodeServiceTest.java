package com.msd.drama.service;

import com.msd.common.exception.BizException;
import com.msd.drama.cache.CacheEvictor;
import com.msd.drama.dto.UnlockRefRequest;
import com.msd.drama.entity.DramaEpisode;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.EpisodeMapper;
import com.msd.drama.mapper.EpisodeUnlockRefMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpisodeServiceTest {

    private EpisodeMapper episodeMapper;
    private EpisodeUnlockRefMapper unlockRefMapper;
    private CacheEvictor cacheEvictor;
    private EpisodeService service;

    @BeforeEach
    void setUp() {
        episodeMapper = mock(EpisodeMapper.class);
        unlockRefMapper = mock(EpisodeUnlockRefMapper.class);
        cacheEvictor = mock(CacheEvictor.class);
        service = new EpisodeService(episodeMapper, unlockRefMapper, mock(DramaMapper.class), cacheEvictor);
    }

    @Test
    void evictsDetailAfterFirstUnlock() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(10L);
        episode.setDramaId(5005L);
        when(episodeMapper.selectById(10L)).thenReturn(episode);
        when(unlockRefMapper.insertIgnore(1L, 10L, 100L)).thenReturn(1);
        when(episodeMapper.incrementUnlockCount(10L)).thenReturn(1);

        boolean first = service.addUnlockRef(10L, new UnlockRefRequest(1L, 100L));

        assertThat(first).isTrue();
        verify(cacheEvictor).evictDetailAfterCommit(5005L);
    }

    @Test
    void duplicateUnlockDoesNotEvict() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(10L);
        episode.setDramaId(5005L);
        when(episodeMapper.selectById(10L)).thenReturn(episode);
        when(unlockRefMapper.insertIgnore(1L, 10L, 100L)).thenReturn(0);

        boolean first = service.addUnlockRef(10L, new UnlockRefRequest(1L, 100L));

        assertThat(first).isFalse();
        verify(cacheEvictor, never()).evictDetailAfterCommit(any());
    }

    @Test
    void missingEpisodeThrowsWithoutEvict() {
        when(episodeMapper.selectById(10L)).thenReturn(null);

        assertThatThrownBy(() -> service.addUnlockRef(10L, new UnlockRefRequest(1L, 100L)))
                .isInstanceOf(BizException.class);
        verify(cacheEvictor, never()).evictDetailAfterCommit(any());
    }
}
