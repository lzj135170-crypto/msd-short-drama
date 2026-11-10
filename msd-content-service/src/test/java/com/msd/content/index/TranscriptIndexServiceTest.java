package com.msd.content.index;

import com.msd.common.dto.EpisodeTranscriptDTO;
import com.msd.common.dto.TranscriptSegmentDTO;
import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.TranscriptChangedEvent;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.RagProperties;
import com.msd.content.rag.Bm25IndexStore;
import com.msd.content.support.LazyMilvusStoreProvider;
import com.msd.content.support.RagIndexException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字幕索引 upsert、删除和失败处理测试。
 */
@SuppressWarnings("unchecked")
class TranscriptIndexServiceTest {

    private final DramaCatalogClient catalogClient = mock(DramaCatalogClient.class);
    private final LazyMilvusStoreProvider milvusProvider = mock(LazyMilvusStoreProvider.class);
    private final MilvusVectorStore milvusStore = mock(MilvusVectorStore.class);
    private final Bm25IndexStore bm25Store = mock(Bm25IndexStore.class);
    private final ObjectProvider<LazyMilvusStoreProvider> milvusHolder =
            mock(ObjectProvider.class);
    private final ObjectProvider<Bm25IndexStore> bm25Holder = mock(ObjectProvider.class);
    private final RagProperties properties = new RagProperties();

    private final TranscriptIndexService service = new TranscriptIndexService(
            catalogClient, new TranscriptDocumentBuilder(), milvusHolder, bm25Holder,
            properties, null);

    /**
     * 默认两路索引可用。
     */
    @BeforeEach
    void stubStores() {
        when(milvusHolder.getIfAvailable()).thenReturn(milvusProvider);
        when(bm25Holder.getIfAvailable()).thenReturn(bm25Store);
        when(milvusProvider.get()).thenReturn(milvusStore);
        when(bm25Store.ping()).thenReturn(true);
    }

    /**
     * UPSERT 先删旧片段，再写入两路索引。
     */
    @Test
    void upsertsTranscriptSegments() {
        when(catalogClient.transcript(6005L)).thenReturn(Result.ok(transcript()));

        service.apply(event(DramaChangedAction.UPSERT));

        verify(milvusProvider).deleteTranscriptsByEpisode(6005L);
        verify(bm25Store).deleteTranscriptsByEpisode(6005L);
        verify(bm25Store).upsertTranscripts(any());
        verify(bm25Store).refresh();
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(milvusStore).add(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    /**
     * 没有字幕时删除旧索引。
     */
    @Test
    void deletesWhenTranscriptEmpty() {
        when(catalogClient.transcript(6005L)).thenReturn(Result.ok(
                new EpisodeTranscriptDTO(5005L, "剧名", 6005L, 1, "第1集", 0, List.of())));

        service.apply(event(DramaChangedAction.UPSERT));

        verify(milvusProvider).deleteTranscriptsByEpisode(6005L);
        verify(bm25Store).deleteTranscriptsByEpisode(6005L);
        verify(milvusStore, never()).add(any());
    }

    /**
     * DELETE 不查询字幕导出。
     */
    @Test
    void deletesWithoutFetching() {
        service.apply(event(DramaChangedAction.DELETE));

        verify(catalogClient, never()).transcript(any());
        verify(milvusProvider).deleteTranscriptsByEpisode(6005L);
        verify(bm25Store).deleteTranscriptsByEpisode(6005L);
    }

    /**
     * 导出失败抛出，交给 Kafka 重试。
     */
    @Test
    void throwsWhenCatalogUnavailable() {
        when(catalogClient.transcript(6005L)).thenThrow(new IllegalStateException("feign down"));

        assertThatThrownBy(() -> service.apply(event(DramaChangedAction.UPSERT)))
                .isInstanceOf(RagIndexException.class)
                .hasMessageContaining("字幕导出调用失败");
    }

    /**
     * 构造字幕导出。
     */
    private EpisodeTranscriptDTO transcript() {
        return new EpisodeTranscriptDTO(5005L, "长安第一女仵作", 6005L, 3, "第3集",
                0, List.of(
                new TranscriptSegmentDTO(1L, 1000, 3000, "第一句"),
                new TranscriptSegmentDTO(2L, 4000, 6000, "第二句")));
    }

    /**
     * 构造事件。
     */
    private TranscriptChangedEvent event(DramaChangedAction action) {
        return new TranscriptChangedEvent("event-1", 6005L, action, System.currentTimeMillis());
    }
}
