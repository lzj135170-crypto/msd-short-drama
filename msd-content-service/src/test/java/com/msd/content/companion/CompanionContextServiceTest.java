package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCompanionClient;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.CompanionContextView;
import com.msd.content.dto.ContextItem;
import com.msd.content.rag.ElasticsearchBm25Store;
import com.msd.content.rag.HybridRetriever;
import com.msd.content.rag.RagMetadata;
import com.msd.content.rag.RagTrace;
import com.msd.content.rag.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 伴看前情检索过滤与降级测试。
 */
class CompanionContextServiceTest {

    private final DramaCompanionClient dramaClient = mock(DramaCompanionClient.class);
    private final HybridRetriever hybridRetriever = mock(HybridRetriever.class);
    private final ElasticsearchBm25Store bm25Store = mock(ElasticsearchBm25Store.class);
    private final CompanionProperties properties = new CompanionProperties();
    private final CompanionAccessService accessService = mock(CompanionAccessService.class);

    private final CompanionContextService service = new CompanionContextService(
            dramaClient, hybridRetriever, bm25Store, properties, accessService);

    /**
     * 默认没有已解锁付费集，测试按需覆盖。
     */
    @BeforeEach
    void noUnlockedEpisodes() {
        when(accessService.unlockedEpisodeIds(any(), any())).thenReturn(Set.of());
    }

    /**
     * 未来集、当前集、跨剧和重复集都必须被后置校验过滤。
     */
    @Test
    void filtersFutureAndCrossDramaDocuments() {
        when(dramaClient.companion(6005L)).thenReturn(Result.ok(meta(6005L, 5001L, 5)));
        when(hybridRetriever.retrieve(any(Query.class), eq(RetrievalScope.episodes(5001L, 5))))
                .thenReturn(List.of(
                        episode(6001L, 1, 5001L, "第1集 闪婚", "第一集摘要", 0.9),
                        episode(6004L, 4, 5001L, "第4集 反击", "第四集摘要", 0.8),
                        episode(6005L, 5, 5001L, "第5集 告白", "当前集", 0.7),
                        episode(6101L, 1, 5002L, "别剧第1集", "跨剧", 0.6),
                        episode(6001L, 1, 5001L, "第1集 闪婚", "重复", 0.5)));

        CompanionContextView view = service.retrieve("他们之前为什么吵架", 6005L, 3);

        assertThat(view.items()).extracting(ContextItem::episodeId)
                .containsExactly(6001L, 6004L);
        assertThat(view.currentEpisodeNo()).isEqualTo(5);
        assertThat(view.degraded()).isFalse();
    }

    /**
     * 前情只保留免费集和已解锁付费集，未解锁付费集不能进入上下文。
     */
    @Test
    void filtersLockedPaidEpisodes() {
        when(dramaClient.companion(6005L)).thenReturn(Result.ok(meta(6005L, 5001L, 5)));
        when(accessService.unlockedEpisodeIds(1003L, 5001L)).thenReturn(Set.of(6003L));
        when(hybridRetriever.retrieve(any(Query.class), any(RetrievalScope.class)))
                .thenReturn(List.of(
                        episode(6002L, 2, 5001L, "第2集", "免费集", 0.9, 0),
                        episode(6003L, 3, 5001L, "第3集", "已解锁", 0.8, 30),
                        episode(6004L, 4, 5001L, "第4集", "未解锁", 0.7, 30)));

        CompanionContextView view = service.retrieve("前情", 6005L, 3, 1003L);

        assertThat(view.items()).extracting(ContextItem::episodeId)
                .containsExactly(6002L, 6003L);
    }

    /**
     * 第 1 集没有前情，不调用检索。
     */
    @Test
    void returnsEmptyWhenCurrentEpisodeIsFirst() {
        when(dramaClient.companion(6001L)).thenReturn(Result.ok(meta(6001L, 5001L, 1)));

        CompanionContextView view = service.retrieve("前情", 6001L, 3);

        assertThat(view.items()).isEmpty();
        assertThat(view.degraded()).isFalse();
    }

    /**
     * 检索为空且 ES 没有分集文档时标记索引缺失。
     */
    @Test
    void marksEpisodeIndexWhenNoDocumentsAndIndexEmpty() {
        when(dramaClient.companion(6002L)).thenReturn(Result.ok(meta(6002L, 5001L, 2)));
        when(hybridRetriever.retrieve(any(Query.class), any(RetrievalScope.class)))
                .thenReturn(List.of());
        when(bm25Store.countEpisodes(5001L)).thenReturn(0L);

        CompanionContextView view = service.retrieve("前情", 6002L, 3);

        assertThat(view.items()).isEmpty();
        assertThat(view.degradedFlags())
                .containsExactly(CompanionContextService.FLAG_EPISODE_INDEX);
    }

    /**
     * 单路检索降级标记原样返回。
     */
    @Test
    void keepsRetrievalDegradedFlags() {
        when(dramaClient.companion(6005L)).thenReturn(Result.ok(meta(6005L, 5001L, 5)));
        when(hybridRetriever.retrieve(any(Query.class), any(RetrievalScope.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    RagTrace trace = RagTrace.from(query);
                    trace.markDegraded("vector");
                    return List.of(episode(6001L, 1, 5001L, "第1集", "摘要", 0.9));
                });

        CompanionContextView view = service.retrieve("前情", 6005L, 3);

        assertThat(view.degraded()).isTrue();
        assertThat(view.degradedFlags()).containsExactly("vector");
        assertThat(view.items()).hasSize(1);
    }

    /**
     * drama 不可用时返回降级视图。
     */
    @Test
    void degradesWhenDramaUnavailable() {
        when(dramaClient.companion(6005L)).thenThrow(new IllegalStateException("drama down"));

        CompanionContextView view = service.retrieve("前情", 6005L, 3);

        assertThat(view.items()).isEmpty();
        assertThat(view.degraded()).isTrue();
        assertThat(view.degradedFlags())
                .containsExactly(CompanionContextService.FLAG_DRAMA);
    }

    /**
     * 剧集不可见时返回 null。
     */
    @Test
    void returnsNullWhenEpisodeNotVisible() {
        when(dramaClient.companion(6005L)).thenReturn(Result.ok(null));

        assertThat(service.retrieve("前情", 6005L, 3)).isNull();
    }

    /**
     * 构造剧集元信息。
     */
    private CompanionEpisodeDTO meta(Long episodeId, Long dramaId, Integer episodeNo) {
        return new CompanionEpisodeDTO(
                episodeId, dramaId, "隐婚总裁的甜妻", episodeNo,
                "第" + episodeNo + "集", "", 2, "简介");
    }

    /**
     * 构造分集检索文档。
     */
    private Document episode(Long episodeId, Integer episodeNo, Long dramaId,
                             String title, String summary, double score) {
        return episode(episodeId, episodeNo, dramaId, title, summary, score, 0);
    }

    /**
     * 构造带价格的分集检索文档。
     */
    private Document episode(Long episodeId, Integer episodeNo, Long dramaId,
                             String title, String summary, double score, int pricePoints) {
        return Document.builder()
                .id("episode-" + episodeId)
                .text(title + " " + summary)
                .metadata(Map.of(
                        RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_EPISODE,
                        RagMetadata.DRAMA_ID, dramaId,
                        RagMetadata.EPISODE_ID, episodeId,
                        RagMetadata.EPISODE_NO, episodeNo,
                        RagMetadata.EPISODE_TITLE, title,
                        RagMetadata.SUMMARY, summary,
                        RagMetadata.PRICE_POINTS, pricePoints,
                        RagMetadata.RRF_SCORE, score,
                        RagMetadata.RETRIEVAL_SOURCES, List.of("vector", "bm25")))
                .build();
    }
}
