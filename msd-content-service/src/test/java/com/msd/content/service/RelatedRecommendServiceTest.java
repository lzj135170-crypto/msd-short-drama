package com.msd.content.service;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.RelatedFeed;
import com.msd.content.rag.RagMetadata;
import com.msd.content.support.LazyMilvusStoreProvider;
import com.msd.content.support.RagUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 相似短剧推荐测试。
 */
class RelatedRecommendServiceTest {

    private final DramaCatalogClient catalogClient = mock(DramaCatalogClient.class);
    private final LazyMilvusStoreProvider milvusProvider = mock(LazyMilvusStoreProvider.class);
    private final RelatedRecommendService service = new RelatedRecommendService(
            catalogClient, milvusProvider, new RagProperties(), null, null);

    /**
     * 向量结果排除自身，并给出共同标签理由。
     */
    @Test
    void returnsVectorRelatedExcludingSelf() {
        when(catalogClient.detail(5001L)).thenReturn(Result.ok(drama(5001L,
                "先婚后爱", List.of("甜宠", "先婚后爱"))));
        when(milvusProvider.search(anyString(), anyInt())).thenReturn(List.of(
                document(5001L, "先婚后爱", List.of("甜宠")),
                document(5002L, "总裁的隐婚甜妻", List.of("甜宠", "先婚后爱")),
                document(5003L, "悬疑迷案", List.of("悬疑"))));

        RelatedFeed feed = service.feed(5001L, 6);

        assertThat(feed.source()).isEqualTo("vector");
        assertThat(feed.degraded()).isFalse();
        assertThat(feed.items()).extracting(item -> item.dramaId())
                .contains(5002L, 5003L)
                .doesNotContain(5001L);
        assertThat(feed.items().get(0).reason()).contains("甜宠");
    }

    /**
     * Milvus 不可用时回退规则推荐并标记降级。
     */
    @Test
    void fallsBackToRuleWhenVectorUnavailable() {
        when(catalogClient.detail(5001L)).thenReturn(Result.ok(drama(5001L,
                "先婚后爱", List.of("甜宠"))));
        when(milvusProvider.search(anyString(), anyInt()))
                .thenThrow(new RagUnavailableException("milvus down"));
        when(catalogClient.relatedFallback(5001L, 6)).thenReturn(Result.ok(List.of(
                drama(5002L, "总裁的隐婚甜妻", List.of("甜宠")))));

        RelatedFeed feed = service.feed(5001L, 6);

        assertThat(feed.source()).isEqualTo("rule");
        assertThat(feed.degraded()).isTrue();
        assertThat(feed.items()).extracting(item -> item.dramaId()).containsExactly(5002L);
    }

    /**
     * 源短剧不存在时返回空结果。
     */
    @Test
    void returnsEmptyWhenSourceMissing() {
        when(catalogClient.detail(5001L)).thenReturn(Result.ok(null));

        RelatedFeed feed = service.feed(5001L, 6);

        assertThat(feed.items()).isEmpty();
        assertThat(feed.degraded()).isFalse();
    }

    /**
     * 构造短剧导出数据。
     */
    private DramaRagExportDTO drama(Long id, String title, List<String> tags) {
        return new DramaRagExportDTO(
                id, title, "副标题", "http://cover/" + id + ".jpg",
                "简介", 10L, "甜宠", tags, List.of("演员"),
                20, List.of("第1集"), null, 100L, 10L, 5L, 2L, List.of());
    }

    /**
     * 构造带 metadata 的短剧文档。
     */
    private Document document(Long dramaId, String title, List<String> tags) {
        return Document.builder()
                .id("drama-" + dramaId)
                .text("标题：" + title)
                .metadata(Map.ofEntries(
                        Map.entry(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_DRAMA),
                        Map.entry(RagMetadata.DRAMA_ID, dramaId),
                        Map.entry(RagMetadata.TITLE, title),
                        Map.entry(RagMetadata.SUBTITLE, "副标题"),
                        Map.entry(RagMetadata.COVER_URL, "http://cover/" + dramaId + ".jpg"),
                        Map.entry(RagMetadata.CATEGORY_NAME, "甜宠"),
                        Map.entry(RagMetadata.TAGS, tags),
                        Map.entry(RagMetadata.EPISODE_COUNT, 20),
                        Map.entry(RagMetadata.VIEW_COUNT, 100),
                        Map.entry(RagMetadata.LIKE_COUNT, 10),
                        Map.entry(RagMetadata.SNIPPET, "简介")))
                .score(0.8)
                .build();
    }
}
