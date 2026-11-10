package com.msd.content.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合测试。
 */
class RrfMergerTest {

    private final RrfMerger merger = new RrfMerger();

    /**
     * 两路命中同一部短剧时只保留一份，并记录两路排名。
     */
    @Test
    void mergesAndDeduplicatesByDocumentId() {
        Document a = document("drama-a", "A", 0.9);
        Document b = document("drama-b", "B", 0.8);
        Document c = document("drama-c", "C", 0.7);

        List<Document> result = merger.merge(List.of(a, b), List.of(b, c), 60, 10);

        assertThat(result).extracting(Document::getId)
                .containsExactly("drama-b", "drama-a", "drama-c");

        Document first = result.get(0);
        assertThat(first.getMetadata())
                .containsEntry(RagMetadata.VECTOR_RANK, 2)
                .containsEntry(RagMetadata.BM25_RANK, 1)
                .containsEntry(RagMetadata.RETRIEVAL_SOURCES, List.of("vector", "bm25"));
        double expected = 1.0 / 62 + 1.0 / 61;
        assertThat((Double) first.getMetadata().get(RagMetadata.RRF_SCORE))
                .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));

        Document second = result.get(1);
        assertThat(second.getMetadata())
                .containsEntry(RagMetadata.VECTOR_RANK, 1)
                .doesNotContainKey(RagMetadata.BM25_RANK);
        assertThat(second.getMetadata().get(RagMetadata.RRF_SCORE))
                .isEqualTo(1.0 / 61);
    }

    /**
     * topK 截断按 RRF 分数降序。
     */
    @Test
    void truncatesToTopK() {
        List<Document> result = merger.merge(
                List.of(document("drama-a", "A", 0.9), document("drama-b", "B", 0.8)),
                List.of(document("drama-b", "B", 0.8), document("drama-c", "C", 0.7)),
                60,
                2);

        assertThat(result).extracting(Document::getId).containsExactly("drama-b", "drama-a");
    }

    /**
     * 同一路重复 ID 只计一次分。
     */
    @Test
    void ignoresDuplicateRanksWithinSameSource() {
        Document b = document("drama-b", "B", 0.8);

        List<Document> result = merger.merge(List.of(b, b), List.of(), 60, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMetadata())
                .containsEntry(RagMetadata.VECTOR_RANK, 1)
                .containsEntry(RagMetadata.RRF_SCORE, 1.0 / 61);
    }

    /**
     * 空输入返回空列表。
     */
    @Test
    void returnsEmptyForEmptyInputs() {
        assertThat(merger.merge(List.of(), null, 60, 10)).isEmpty();
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
