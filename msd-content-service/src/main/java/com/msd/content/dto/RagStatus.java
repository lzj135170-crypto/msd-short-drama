package com.msd.content.dto;

/**
 * RAG 链路状态。
 *
 * @param enabled              RAG 功能是否开启
 * @param vectorAvailable      Milvus 向量库是否可用
 * @param bm25Available        Elasticsearch BM25 索引是否可用
 * @param milvusCollection     当前使用的 Milvus collection 名称
 * @param elasticsearchIndex   当前使用的 ES 索引名称
 * @param lastIndexedAt        最近一次索引完成时间，未索引过为 null
 * @param lastDramaCount       最近一次索引的短剧数量
 * @param lastEpisodeCount     最近一次索引的分集数量
 * @param lastPartial          最近一次索引是否有任一路失败
 * @param lastError            最近一次索引的错误信息，正常时为 null
 */
public record RagStatus(
        boolean enabled,
        boolean vectorAvailable,
        boolean bm25Available,
        String milvusCollection,
        String elasticsearchIndex,
        String lastIndexedAt,
        int lastDramaCount,
        int lastEpisodeCount,
        boolean lastPartial,
        String lastError
) {
}
