package com.msd.content.audit;

import java.util.List;

/**
 * sidecar 返回的标签建议。
 */
public record RexTag(
        String name,
        int support,
        List<String> evidence
) {

    /**
     * 统一空值。
     */
    public RexTag {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
