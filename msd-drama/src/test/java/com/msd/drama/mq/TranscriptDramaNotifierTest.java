package com.msd.drama.mq;

import com.msd.common.event.DramaChangedAction;
import com.msd.drama.mapper.EpisodeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核通过后字幕补发测试。
 */
class TranscriptDramaNotifierTest {

    private final EpisodeMapper episodeMapper = mock(EpisodeMapper.class);
    private final TranscriptChangedPublisher publisher = mock(TranscriptChangedPublisher.class);
    private final TranscriptDramaNotifier notifier =
            new TranscriptDramaNotifier(episodeMapper, publisher);

    /**
     * 为有字幕的剧集逐条发布 UPSERT。
     */
    @Test
    void publishesForEpisodesWithTranscript() {
        when(episodeMapper.selectEpisodeIdsWithTranscript(5005L))
                .thenReturn(List.of(6001L, 6002L));

        notifier.publishForDrama(5005L);

        verify(publisher).publishAfterCommit(6001L, DramaChangedAction.UPSERT);
        verify(publisher).publishAfterCommit(6002L, DramaChangedAction.UPSERT);
    }

    /**
     * 查询失败不影响审核主流程。
     */
    @Test
    void swallowsLookupFailure() {
        when(episodeMapper.selectEpisodeIdsWithTranscript(5005L))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> notifier.publishForDrama(5005L)).doesNotThrowAnyException();
    }
}
