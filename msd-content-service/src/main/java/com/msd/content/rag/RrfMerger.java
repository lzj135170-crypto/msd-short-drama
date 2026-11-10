package com.msd.content.rag;

import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion 融合器。
 *
 * <p>按 Document.id 聚合两路结果，score += 1/(k+rank)，rank 从 1 开始。
 * 同一部短剧同时命中向量和 BM25 时只保留一份文档，并记录两路排名。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RrfMerger {

    /**
     * 融合向量结果和 BM25 结果。
     *
     * @param vectorDocs 向量检索结果，可为空
     * @param bm25Docs   BM25 检索结果，可为空
     * @param k          RRF 平滑常数
     * @param topK       返回条数
     */
    public List<Document> merge(List<Document> vectorDocs, List<Document> bm25Docs, int k, int topK) {
        int safeK = Math.max(k, 1);
        int safeTopK = Math.max(topK, 1);
        Map<String, Accumulator> byId = new LinkedHashMap<>();

        // 1. 先应用向量排名，再应用 BM25 排名，保证 retrievalSources 顺序稳定
        apply(vectorDocs, "vector", safeK, byId);
        apply(bm25Docs, "bm25", safeK, byId);

        // 2. 按 RRF 分数降序，分数相同时按文档 ID 排序，保证结果可复现
        return byId.values().stream()
                .sorted(Comparator.comparingDouble((Accumulator acc) -> acc.score).reversed()
                        .thenComparing(acc -> acc.first.getId(),
                                Comparator.nullsLast(String::compareTo)))
                .limit(safeTopK)
                .map(Accumulator::toDocument)
                .toList();
    }

    /**
     * 把一路结果累加到聚合表。
     */
    private void apply(List<Document> documents, String source, int k, Map<String, Accumulator> byId) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            if (document == null || document.getId() == null) {
                continue;
            }

            int rank = index + 1;
            Accumulator accumulator = byId.computeIfAbsent(
                    document.getId(), id -> new Accumulator(document));

            // 1. 同一路重复 ID 只取第一次排名，避免分数被重复累加
            if ("vector".equals(source) && accumulator.vectorRank != null) {
                continue;
            }
            if ("bm25".equals(source) && accumulator.bm25Rank != null) {
                continue;
            }

            // 2. RRF 累加
            accumulator.score += 1.0 / (k + rank);
            accumulator.sources.add(source);
            accumulator.mergeMissing(document.getMetadata());

            // 3. 记录两路排名和原始分数
            if ("vector".equals(source)) {
                accumulator.vectorRank = rank;
                if (document.getScore() != null) {
                    accumulator.vectorScore = document.getScore();
                }
            } else {
                accumulator.bm25Rank = rank;
                Object score = document.getMetadata().get(RagMetadata.BM25_SCORE);
                if (score instanceof Number number) {
                    accumulator.bm25Score = number.doubleValue();
                }
            }
        }
    }

    /**
     * 单部短剧的 RRF 聚合状态。
     */
    private static final class Accumulator {

        // 首次出现的文档，保留其正文
        private final Document first;
        // 后续来源补充的缺失 metadata
        private final Map<String, Object> extraMetadata = new LinkedHashMap<>();
        // 命中的检索来源，按顺序去重
        private final Set<String> sources = new LinkedHashSet<>();

        // 累计的 RRF 分数
        private double score;
        // 向量检索排名
        private Integer vectorRank;
        // BM25 检索排名
        private Integer bm25Rank;
        // 向量原始分数
        private Double vectorScore;
        // BM25 原始分数
        private Double bm25Score;

        /** 使用首次命中的文档初始化聚合状态。 */
        private Accumulator(Document first) {
            this.first = first;
        }

        /**
         * 补全第一次出现时没有的 metadata，保留首次文档的正文。
         */
        private void mergeMissing(Map<String, Object> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return;
            }
            // 1. 只补充首次文档缺失的键，避免覆盖已有值
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String key = entry.getKey();
                if (!first.getMetadata().containsKey(key) && !extraMetadata.containsKey(key)) {
                    extraMetadata.put(key, entry.getValue());
                }
            }
        }

        /**
         * 生成带 RRF 排名信息的文档。
         */
        private Document toDocument() {
            // 1. 合并首次文档和后续来源补充的 metadata
            Map<String, Object> metadata = new LinkedHashMap<>(first.getMetadata());
            metadata.putAll(extraMetadata);
            // 2. 写入 RRF 分数、两路排名和原始分数
            metadata.put(RagMetadata.RRF_SCORE, score);
            if (vectorRank != null) {
                metadata.put(RagMetadata.VECTOR_RANK, vectorRank);
            }
            if (bm25Rank != null) {
                metadata.put(RagMetadata.BM25_RANK, bm25Rank);
            }
            if (vectorScore != null) {
                metadata.put(RagMetadata.VECTOR_SCORE, vectorScore);
            }
            if (bm25Score != null) {
                metadata.put(RagMetadata.BM25_SCORE, bm25Score);
            }
            metadata.put(RagMetadata.RETRIEVAL_SOURCES, List.copyOf(sources));

            return Document.builder()
                    .id(first.getId())
                    .text(first.getText())
                    .metadata(metadata)
                    .score(score)
                    .build();
        }
    }
}
