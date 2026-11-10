package com.msd.content.dto;

import java.util.List;

/**
 * 一次索引重建的结果。
 *
 * @param dramaCount      从短剧服务导出的短剧总数
 * @param episodeCount    从短剧服务导出的分集总数
 * @param vectorUpserted  写入 Milvus 的向量条数
 * @param esIndexed       写入 Elasticsearch 的文档条数
 * @param staleDeleted    从 Milvus 清理的已下架向量条数
 * @param vectorAvailable 重建后 Milvus 是否可用
 * @param bm25Available   重建后 Elasticsearch 是否可用
 * @param partial         是否有任一路索引失败或不可用
 * @param errors          重建过程中收集的错误信息
 * @param elapsedMs       重建耗时（毫秒）
 */
public record ReindexResult(
        int dramaCount,
        int episodeCount,
        int vectorUpserted,
        int esIndexed,
        int staleDeleted,
        boolean vectorAvailable,
        boolean bm25Available,
        boolean partial,
        List<String> errors,
        long elapsedMs
) {
}
