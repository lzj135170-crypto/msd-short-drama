package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨查询 RRF 融合：每个 query 的结果先按自身排名计分，再按 Document.id 去重合并。
 *
 * <p>单 query 直接返回原结果，保持现有检索行为不变。</p>
 */
public class RrfDocumentJoiner implements DocumentJoiner {

    // RAG 配置
    private final RagProperties properties;

    /**
     * 注入 RAG 配置。
     */
    public RrfDocumentJoiner(RagProperties properties) {
        this.properties = properties;
    }

    /**
     * 融合多查询结果。
     */
    @Override
    public List<Document> join(Map<Query, List<List<Document>>> documentsByQuery) {
        // 1. 空结果直接返回
        if (documentsByQuery == null || documentsByQuery.isEmpty()) {
            return List.of();
        }

        // 2. 单 query 单路结果保持原样，避免改变现有 RRF 行为
        if (documentsByQuery.size() == 1) {
            List<List<Document>> lists = documentsByQuery.values().iterator().next();
            if (lists == null || lists.isEmpty()) {
                return List.of();
            }
            if (lists.size() == 1) {
                return lists.get(0) == null ? List.of() : lists.get(0);
            }
        }

        int k = Math.max(properties.getHybrid().getRrfK(), 1);
        int topK = Math.max(properties.getHybrid().getTopK(), 1);
        Map<String, Accumulator> byId = new LinkedHashMap<>();

        // 3. 每个 query 独立排名，避免不同 query 的原始分数不可比
        for (Map.Entry<Query, List<List<Document>>> entry : documentsByQuery.entrySet()) {
            Set<String> seenInQuery = new LinkedHashSet<>();
            List<List<Document>> lists = entry.getValue();
            if (lists == null) {
                continue;
            }
            for (List<Document> documents : lists) {
                if (documents == null) {
                    continue;
                }
                for (int index = 0; index < documents.size(); index++) {
                    Document document = documents.get(index);
                    if (document == null || document.getId() == null
                            || !seenInQuery.add(document.getId())) {
                        continue;
                    }
                    int rank = index + 1;
                    Accumulator accumulator = byId.computeIfAbsent(
                            document.getId(), id -> new Accumulator(document));
                    accumulator.score += 1.0 / (k + rank);
                    accumulator.queryHits++;
                    if (accumulator.queryRank == null) {
                        accumulator.queryRank = rank;
                    }
                    accumulator.mergeMissing(document.getMetadata());
                }
            }
        }

        // 4. 按融合分数排序，分数相同按 ID 保证可复现
        return byId.values().stream()
                .sorted(Comparator.comparingDouble((Accumulator item) -> item.score).reversed()
                        .thenComparing(item -> item.first.getId(),
                                Comparator.nullsLast(String::compareTo)))
                .limit(topK)
                .map(Accumulator::toDocument)
                .toList();
    }

    /**
     * 单文档跨查询聚合状态。
     */
    private static final class Accumulator {

        // 首次出现的文档，保留正文
        private final Document first;
        // 后续查询补充的缺失 metadata
        private final Map<String, Object> extraMetadata = new LinkedHashMap<>();
        // 命中的查询数
        private int queryHits;
        // 首次命中的 query 排名
        private Integer queryRank;
        // 跨查询 RRF 分数
        private double score;

        /**
         * 使用首次命中的文档初始化。
         */
        private Accumulator(Document first) {
            this.first = first;
        }

        /**
         * 补充首次文档缺失的 metadata。
         */
        private void mergeMissing(Map<String, Object> metadata) {
            if (metadata == null) {
                return;
            }
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String key = entry.getKey();
                if (!first.getMetadata().containsKey(key) && !extraMetadata.containsKey(key)) {
                    extraMetadata.put(key, entry.getValue());
                }
            }
        }

        /**
         * 生成带跨查询分数的文档。
         */
        private Document toDocument() {
            Map<String, Object> metadata = new LinkedHashMap<>(first.getMetadata());
            metadata.putAll(extraMetadata);
            metadata.put(RagMetadata.MULTI_QUERY_SCORE, score);
            metadata.put(RagMetadata.QUERY_HITS, queryHits);
            if (queryRank != null) {
                metadata.put(RagMetadata.QUERY_RANK, queryRank);
            }
            metadata.put(RagMetadata.RETRIEVAL_SOURCES,
                    mergeSources(first.getMetadata().get(RagMetadata.RETRIEVAL_SOURCES)));

            return Document.builder()
                    .id(first.getId())
                    .text(first.getText())
                    .metadata(metadata)
                    .score(score)
                    .build();
        }

        /**
         * 保留原有 vector/bm25 来源并追加 multi_query。
         */
        private List<String> mergeSources(Object value) {
            Set<String> sources = new LinkedHashSet<>();
            if (value instanceof List<?> values) {
                for (Object item : values) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        sources.add(String.valueOf(item));
                    }
                }
            }
            sources.add("multi_query");
            return new ArrayList<>(sources);
        }
    }
}
