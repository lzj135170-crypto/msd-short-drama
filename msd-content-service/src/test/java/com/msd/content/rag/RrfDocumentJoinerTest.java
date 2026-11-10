package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨查询 RRF 融合测试。
 */
class RrfDocumentJoinerTest {

    /**
     * 多查询结果按文档 ID 去重，并累加跨查询排名分。
     */
    @Test
    void mergesMultipleQueriesByRank() {
        RrfDocumentJoiner joiner = new RrfDocumentJoiner(new RagProperties());
        Query first = new Query("q1");
        Query second = new Query("q2");

        Map<Query, List<List<Document>>> results = new LinkedHashMap<>();
        results.put(first, List.of(List.of(document("a"), document("b"))));
        results.put(second, List.of(List.of(document("b"), document("c"))));

        List<Document> merged = joiner.join(results);

        assertThat(merged).extracting(Document::getId).containsExactly("b", "a", "c");
        assertThat(merged.get(0).getMetadata()).containsKey(RagMetadata.MULTI_QUERY_SCORE);
        assertThat(merged.get(0).getMetadata().get(RagMetadata.QUERY_HITS)).isEqualTo(2);
    }

    /**
     * 单查询单路结果保持原样，避免改变现有行为。
     */
    @Test
    void keepsSingleQueryResultUnchanged() {
        RrfDocumentJoiner joiner = new RrfDocumentJoiner(new RagProperties());
        List<Document> documents = List.of(document("a"), document("b"));

        List<Document> merged = joiner.join(Map.of(new Query("q"), List.of(documents)));

        assertThat(merged).isSameAs(documents);
    }

    /**
     * 创建带 ID 的文档。
     */
    private Document document(String id) {
        return Document.builder()
                .id(id)
                .text(id)
                .metadata(Map.of(RagMetadata.TITLE, id))
                .score(1.0)
                .build();
    }
}
