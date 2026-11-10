package com.msd.content.audit;

import java.util.List;

/**
 * sidecar 返回的情感结果。
 */
public record RexSentiment(
        String label,
        String rawLabel,
        boolean highNegative,
        int support,
        List<String> evidence
) {

    /**
     * 统一空值。
     */
    public RexSentiment {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
