package com.msd.interaction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * content 结构化审核结论。
 *
 * @param isViolation     是否存在违规风险
 * @param violationReason 违规原因
 * @param tags            命中标签
 */
public record CommentAuditVerdict(
        @JsonProperty("is_violation") boolean isViolation,
        @JsonProperty("violation_reason") String violationReason,
        @JsonProperty("tags") List<String> tags
) {

    /**
     * 统一空值。
     */
    public CommentAuditVerdict {
        violationReason = violationReason == null ? "" : violationReason;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
