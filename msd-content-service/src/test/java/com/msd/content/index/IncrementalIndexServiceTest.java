package com.msd.content.index;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.DramaChangedEvent;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.IndexUpdateResult;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 增量索引的 upsert、删除、下架回退和部分失败测试。
 */
@SuppressWarnings("unchecked")
class IncrementalIndexServiceTest {

    private final DramaCatalogClient catalogClient = mock(DramaCatalogClient.class);
    private final LazyMilvusStoreProvider milvusProvider = mock(LazyMilvusStoreProvider.class);
    private final MilvusVectorStore milvusStore = mock(MilvusVectorStore.class);
    private final Bm25IndexStore bm25Store = mock(Bm25IndexStore.class);
    private final ObjectProvider<LazyMilvusStoreProvider> milvusProviderHolder =
            mock(ObjectProvider.class);
    private final ObjectProvider<Bm25IndexStore> bm25ProviderHolder =
            mock(ObjectProvider.class);
    private final RagProperties properties = new RagProperties();

    private final IncrementalIndexService service = new IncrementalIndexService(
            catalogClient, new DramaDocumentBuilder(properties),
            milvusProviderHolder, bm25ProviderHolder, properties, null);

    /**
     * 默认两路索引都可用。
     */
    @BeforeEach
    void stubStores() {
        when(milvusProviderHolder.getIfAvailable()).thenReturn(milvusProvider);
        when(bm25ProviderHolder.getIfAvailable()).thenReturn(bm25Store);
        when(milvusProvider.get()).thenReturn(milvusStore);
        when(bm25Store.ping()).thenReturn(true);
    }

    /**
     * UPSERT 先清理旧向量，再写入短剧和分集文档，同时重建 ES 文档。
     */
    @Test
    void upsertIndexesDramaAndEpisodes() {
        DramaRagExportDTO drama = drama();
        when(catalogClient.detail(5005L)).thenReturn(Result.ok(drama));

        IndexUpdateResult result = service.apply(event(DramaChangedAction.UPSERT));

        assertThat(result.deleted()).isFalse();
        assertThat(result.episodeCount()).isEqualTo(2);
        assertThat(result.partial()).isFalse();
        verify(milvusProvider).deleteByDrama(5005L);
        verify(bm25Store).deleteByDrama(5005L);
        verify(bm25Store).indexAll(List.of(drama));
        verify(bm25Store).refresh();

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(milvusStore).add(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        assertThat(captor.getValue()).extracting(Document::getId)
                .containsExactly("drama-5005", "episode-6401", "episode-6402");
    }

    /**
     * DELETE 不查询目录，直接清理两路索引。
     */
    @Test
    void deleteRemovesBothIndexesWithoutCatalogCall() {
        IndexUpdateResult result = service.apply(event(DramaChangedAction.DELETE));

        assertThat(result.deleted()).isTrue();
        assertThat(result.partial()).isFalse();
        verify(catalogClient, never()).detail(anyLong());
        verify(milvusProvider).deleteByDrama(5005L);
        verify(bm25Store).deleteByDrama(5005L);
    }

    /**
     * UPSERT 时短剧已不可见，自动降级为删除。
     */
    @Test
    void upsertFallsBackToDeleteWhenDramaInvisible() {
        when(catalogClient.detail(5005L)).thenReturn(Result.ok(null));

        IndexUpdateResult result = service.apply(event(DramaChangedAction.UPSERT));

        assertThat(result.deleted()).isTrue();
        verify(milvusProvider).deleteByDrama(5005L);
        verify(bm25Store).deleteByDrama(5005L);
        verify(milvusStore, never()).add(any());
    }

    /**
     * ES 索引不存在时不能直接写入，避免自动创建错误 mapping。
     */
    @Test
    void failsWhenBm25IndexMissing() {
        when(catalogClient.detail(5005L)).thenReturn(Result.ok(drama()));
        when(bm25Store.ping()).thenReturn(false);

        assertThatThrownBy(() -> service.apply(event(DramaChangedAction.UPSERT)))
                .isInstanceOf(RagIndexException.class)
                .hasMessageContaining("bm25");
        verify(bm25Store, never()).indexAll(any());
    }

    /**
     * 目录不可用时抛出，交给调度器重试。
     */
    @Test
    void throwsWhenCatalogUnavailable() {
        when(catalogClient.detail(5005L)).thenThrow(new IllegalStateException("feign down"));

        assertThatThrownBy(() -> service.apply(event(DramaChangedAction.UPSERT)))
                .isInstanceOf(RagIndexException.class)
                .hasMessageContaining("短剧导出调用失败");
        verify(bm25Store, never()).indexAll(any());
    }

    /**
     * 向量失败后仍尝试 BM25，最后统一抛出触发重试。
     */
    @Test
    void attemptsBothIndexesBeforeThrowing() {
        when(catalogClient.detail(5005L)).thenReturn(Result.ok(drama()));
        doThrowOnVectorDelete();

        assertThatThrownBy(() -> service.apply(event(DramaChangedAction.UPSERT)))
                .isInstanceOf(RagIndexException.class)
                .hasMessageContaining("vector");
        verify(bm25Store).indexAll(any());
        verify(bm25Store).refresh();
    }

    /**
     * 让 Milvus 删除失败。
     */
    private void doThrowOnVectorDelete() {
        org.mockito.Mockito.doThrow(new IllegalStateException("milvus down"))
                .when(milvusProvider).deleteByDrama(5005L);
    }

    /**
     * 构造变更事件。
     */
    private DramaChangedEvent event(DramaChangedAction action) {
        return new DramaChangedEvent("event-1", 5005L, action, System.currentTimeMillis());
    }

    /**
     * 构造含两集明细的短剧。
     */
    private DramaRagExportDTO drama() {
        return new DramaRagExportDTO(
                5005L,
                "长安第一女仵作",
                "验尸破案",
                "http://127.0.0.1:9000/msd/covers/5005.jpg",
                "长安城接连出现离奇命案。",
                3L,
                "古装悬疑",
                List.of("悬疑", "破案"),
                List.of("林一", "苏晚"),
                2,
                List.of("第1集 验尸", "第2集 追凶"),
                "2026-01-01T10:00:00",
                1000L,
                100L,
                50L,
                20L,
                List.of(
                        new EpisodeRagExportDTO(6401L, 1, "第1集 验尸", "雨夜验尸", 0),
                        new EpisodeRagExportDTO(6402L, 2, "第2集 追凶", "顺藤摸瓜", 30)));
    }
}
