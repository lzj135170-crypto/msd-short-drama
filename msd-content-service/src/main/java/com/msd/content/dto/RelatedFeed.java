package com.msd.content.dto;

import java.util.List;

/**
 * 相似短剧推荐结果。
 *
 * @param items    推荐卡片
 * @param source   召回来源：vector 向量，rule 规则兜底
 * @param degraded 是否因向量检索不可用而降级
 */
public record RelatedFeed(
        List<RelatedDramaCard> items,
        String source,
        boolean degraded
) {

    /**
     * 统一空列表。
     */
    public RelatedFeed {
        items = items == null ? List.of() : List.copyOf(items);
        source = source == null ? "" : source;
    }

    /**
     * 空结果。
     */
    public static RelatedFeed empty(boolean degraded) {
        return new RelatedFeed(List.of(), degraded ? "rule" : "", degraded);
    }
}
