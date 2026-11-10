package com.msd.content.audit;

import java.util.List;

/**
 * 审核台展示的标签建议，不写入 t_drama_tag_rel。
 *
 * @param name     标签名称
 * @param support  命中片数
 * @param evidence 证据片段
 */
public record DramaTagSuggestion(
        String name,
        int support,
        List<String> evidence
) {

    /**
     * 统一空值，避免写入 null 数组。
     */
    public DramaTagSuggestion {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
