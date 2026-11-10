package com.msd.content.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 结构化审核结论，字段名严格对齐模型输出的 JSON。
 *
 * @param isViolation     是否存在违规风险
 * @param violationReason 违规原因，未违规时为空字符串
 * @param tags            命中标签，未违规时为空数组
 */
public record AuditVerdict(
        @JsonProperty("is_violation")
        @JsonPropertyDescription("是否存在违规风险")
        boolean isViolation,

        @JsonProperty("violation_reason")
        @JsonPropertyDescription("违规原因，未违规时为空字符串")
        String violationReason,

        @JsonProperty("tags")
        @JsonPropertyDescription("命中标签，未违规时为空数组")
        List<String> tags
) {

    /**
     * 统一空值，避免调用方判空。
     */
    public AuditVerdict {
        violationReason = violationReason == null ? "" : violationReason;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
