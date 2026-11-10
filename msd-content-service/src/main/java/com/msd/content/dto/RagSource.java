package com.msd.content.dto;

import java.util.List;

/**
 * 推荐回答引用的短剧来源。
 *
 * @param dramaId          短剧 ID
 * @param title            短剧标题
 * @param subtitle         短剧副标题
 * @param coverUrl         封面地址
 * @param snippet          展示用摘要，优先取 metadata，缺失时截断正文
 * @param score            排序得分，依次取 rerank 分、RRF 分、向量分
 * @param rrfScore         RRF 融合得分
 * @param vectorRank       向量检索中的排名，未命中时为 null
 * @param bm25Rank         BM25 检索中的排名，未命中时为 null
 * @param retrievalSources 命中的检索通道，如 vector、bm25
 */
public record RagSource(
        Long dramaId,
        String title,
        String subtitle,
        String coverUrl,
        String snippet,
        Double score,
        Double rrfScore,
        Integer vectorRank,
        Integer bm25Rank,
        List<String> retrievalSources
) {
}
