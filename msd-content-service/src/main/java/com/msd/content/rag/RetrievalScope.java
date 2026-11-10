package com.msd.content.rag;

/**
 * 检索范围：推荐链路只搜短剧，AI 伴看只搜当前集之前的分集。
 *
 * @param episodeScope     是否检索分集；false 表示检索短剧
 * @param dramaId          分集检索限定的短剧 ID
 * @param beforeEpisodeNo  只返回集数小于该值的分集
 */
public record RetrievalScope(
        boolean episodeScope,
        Long dramaId,
        Integer beforeEpisodeNo
) {

    /**
     * 推荐链路的短剧检索范围。
     */
    public static RetrievalScope drama() {
        return new RetrievalScope(false, null, null);
    }

    /**
     * AI 伴看的前情检索范围。
     */
    public static RetrievalScope episodes(Long dramaId, Integer beforeEpisodeNo) {
        return new RetrievalScope(true, dramaId, beforeEpisodeNo);
    }
}
