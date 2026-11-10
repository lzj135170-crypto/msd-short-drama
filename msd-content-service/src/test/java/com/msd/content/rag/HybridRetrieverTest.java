package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import com.msd.content.support.LazyMilvusStoreProvider;
import com.msd.content.support.RagUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 混合检索降级与 RRF 融合测试。
 */
class HybridRetrieverTest {

    private final RagProperties properties = new RagProperties();
    private final LazyMilvusStoreProvider milvus = mock(LazyMilvusStoreProvider.class);
    private final ElasticsearchBm25Store bm25 = mock(ElasticsearchBm25Store.class);
    private final RrfMerger merger = new RrfMerger();
    private final Executor directExecutor = Runnable::run;

    /**
     * 两路都成功时按 RRF 融合并去重。
     */
    @Test
    void mergesBothBranches() {
        when(milvus.search("q", 20)).thenReturn(List.of(
                document("drama-a", "A", 0.9),
                document("drama-b", "B", 0.8)));
        when(bm25.search("q", 20)).thenReturn(List.of(
                document("drama-b", "B", 4.0),
                document("drama-c", "C", 3.0)));

        RagTrace trace = new RagTrace("q");
        List<Document> result = retriever().retrieve(query(trace));

        assertThat(result).extracting(Document::getId)
                .containsExactly("drama-b", "drama-a", "drama-c");
        assertThat(trace.getVectorHits()).isEqualTo(2);
        assertThat(trace.getBm25Hits()).isEqualTo(2);
        assertThat(trace.getRrfHits()).isEqualTo(3);
        assertThat(trace.getDegradedFlags()).isEmpty();
    }

    /**
     * 向量分支失败时 BM25 结果仍然返回。
     */
    @Test
    void keepsBm25WhenVectorFails() {
        when(milvus.search("q", 20)).thenThrow(new RagUnavailableException("milvus down"));
        when(bm25.search("q", 20)).thenReturn(List.of(document("drama-c", "C", 3.0)));

        RagTrace trace = new RagTrace("q");
        List<Document> result = retriever().retrieve(query(trace));

        assertThat(result).extracting(Document::getId).containsExactly("drama-c");
        assertThat(trace.getVectorHits()).isZero();
        assertThat(trace.getBm25Hits()).isEqualTo(1);
        assertThat(trace.getDegradedFlags()).containsExactly("vector");
    }

    /**
     * 两路都失败时返回空列表，并记录两个降级标记。
     */
    @Test
    void returnsEmptyWhenBothBranchesFail() {
        when(milvus.search("q", 20)).thenThrow(new RagUnavailableException("milvus down"));
        when(bm25.search("q", 20)).thenThrow(new RagUnavailableException("es down"));

        RagTrace trace = new RagTrace("q");
        List<Document> result = retriever().retrieve(query(trace));

        assertThat(result).isEmpty();
        assertThat(trace.getDegradedFlags()).containsExactly("vector", "bm25");
    }

    /**
     * 关闭向量分支时不产生降级标记。
     */
    @Test
    void skipsDisabledVectorBranch() {
        properties.getVector().setEnabled(false);
        when(bm25.search("q", 20)).thenReturn(List.of(document("drama-c", "C", 3.0)));

        RagTrace trace = new RagTrace("q");
        List<Document> result = retriever().retrieve(query(trace));

        assertThat(result).extracting(Document::getId).containsExactly("drama-c");
        assertThat(trace.getVectorHits()).isZero();
        assertThat(trace.getDegradedFlags()).isEmpty();
    }

    /**
     * 伴看范围走分集检索，不会调用短剧检索。
     */
    @Test
    void usesEpisodeScopeWhenRequested() {
        when(milvus.searchEpisodes("q", 5005L, 5, 20))
                .thenReturn(List.of(document("episode-6401", "前情", 0.9)));
        when(bm25.searchEpisodes("q", 5005L, 5, 20))
                .thenReturn(List.of(document("episode-6401", "前情", 3.0)));

        RagTrace trace = new RagTrace("q");
        List<Document> result = retriever()
                .retrieve(query(trace), RetrievalScope.episodes(5005L, 5));

        assertThat(result).extracting(Document::getId).containsExactly("episode-6401");
        verify(milvus).searchEpisodes("q", 5005L, 5, 20);
        verify(bm25).searchEpisodes("q", 5005L, 5, 20);
        verify(milvus, never()).search("q", 20);
        verify(bm25, never()).search("q", 20);
    }

    /**
     * 构造被测检索器。
     */
    private HybridRetriever retriever() {
        return new HybridRetriever(properties, milvus, bm25, merger, directExecutor);
    }

    /**
     * 构造带 trace 的查询。
     */
    private Query query(RagTrace trace) {
        return Query.builder()
                .text("q")
                .context(Map.of(RagTrace.CONTEXT_KEY, trace))
                .build();
    }

    /**
     * 构造测试文档。
     */
    private Document document(String id, String text, double score) {
        return Document.builder()
                .id(id)
                .text(text)
                .metadata(Map.of(RagMetadata.TITLE, text))
                .score(score)
                .build();
    }
}
