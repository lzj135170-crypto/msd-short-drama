package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.TranscriptCitation;
import com.msd.content.dto.TranscriptContextView;
import com.msd.content.rag.HybridRetriever;
import com.msd.content.rag.RagMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字幕检索的防剧透、付费过滤和空场景测试。
 */
class TranscriptContextServiceTest {

    private final HybridRetriever hybridRetriever = mock(HybridRetriever.class);
    private final CompanionAccessService accessService = mock(CompanionAccessService.class);
    private final CompanionProperties properties = new CompanionProperties();

    private final TranscriptContextService service = new TranscriptContextService(
            hybridRetriever, accessService, properties);

    /**
     * 只返回当前播放位置之前的字幕：前集全部可见，当前集只看 endMs。
     */
    @Test
    void filtersByPlayheadAndEpisode() {
        when(hybridRetriever.retrieveTranscripts(any(), eq(5005L), eq(3))).thenReturn(List.of(
                document("t-1", 2, 6002L, 0, 120_000, 0),
                document("t-2", 3, 6003L, 10_000, 50_000, 0),
                document("t-3", 3, 6003L, 50_000, 70_000, 0),
                document("t-4", 4, 6004L, 0, 10_000, 0)));

        TranscriptContextView view = service.retrieve(
                "结尾发生了什么", meta(), 60_000L, 5, 1003L);

        assertThat(view.items()).extracting(TranscriptCitation::episodeNo)
                .containsExactly(2, 3);
        assertThat(view.items()).extracting(TranscriptCitation::startMs)
                .containsExactly(0, 10_000);
    }

    /**
     * 未解锁的付费前情被过滤，解锁后恢复。
     */
    @Test
    void filtersPaidPreviousEpisode() {
        Document paid = document("t-paid", 2, 6002L, 0, 100_000, 30);
        when(hybridRetriever.retrieveTranscripts(any(), eq(5005L), eq(3)))
                .thenReturn(List.of(paid));
        when(accessService.unlockedEpisodeIds(1003L, 5005L)).thenReturn(Set.of());

        TranscriptContextView locked = service.retrieve(
                "之前发生了什么", meta(), 0L, 5, 1003L);

        assertThat(locked.items()).isEmpty();

        when(accessService.unlockedEpisodeIds(1003L, 5005L)).thenReturn(Set.of(6002L));
        TranscriptContextView unlocked = service.retrieve(
                "之前发生了什么", meta(), 0L, 5, 1003L);

        assertThat(unlocked.items()).hasSize(1);
    }

    /**
     * 第 1 集未播放时没有可引用的字幕，且不触发检索。
     */
    @Test
    void skipsFirstEpisodeBeforePlayback() {
        CompanionEpisodeDTO first = new CompanionEpisodeDTO(
                6001L, 5005L, "长安第一女仵作", 1, "第1集", "", 2, "", 0);

        TranscriptContextView view = service.retrieve("这一集讲了什么", first, 0L, 3, 1003L);

        assertThat(view.items()).isEmpty();
        verify(hybridRetriever, never()).retrieveTranscripts(any(), any(), any());
    }

    /**
     * 构造字幕 Document。
     */
    private Document document(String id, int episodeNo, Long episodeId,
                              int startMs, int endMs, int price) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_TRANSCRIPT);
        metadata.put(RagMetadata.DRAMA_ID, 5005L);
        metadata.put(RagMetadata.EPISODE_ID, episodeId);
        metadata.put(RagMetadata.EPISODE_NO, episodeNo);
        metadata.put(RagMetadata.EPISODE_TITLE, "第" + episodeNo + "集");
        metadata.put(RagMetadata.START_MS, startMs);
        metadata.put(RagMetadata.END_MS, endMs);
        metadata.put(RagMetadata.PRICE_POINTS, price);
        metadata.put(RagMetadata.TRANSCRIPT, "字幕内容");
        metadata.put(RagMetadata.RRF_SCORE, 0.9);
        return Document.builder().id(id).text("字幕内容").metadata(metadata).build();
    }

    /**
     * 当前第 3 集元信息。
     */
    private CompanionEpisodeDTO meta() {
        return new CompanionEpisodeDTO(
                6003L, 5005L, "长安第一女仵作", 3, "第3集", "", 2, "", 0);
    }
}
