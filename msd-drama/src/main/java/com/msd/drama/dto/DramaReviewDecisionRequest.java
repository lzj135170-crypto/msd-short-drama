package com.msd.drama.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理员人工审核决策。
 *
 * @param decision APPROVE 通过或 REJECT 驳回
 * @param remark   审核意见，驳回时必填
 */
public record DramaReviewDecisionRequest(
        @NotBlank(message = "审核决策不能为空")
        @Pattern(regexp = "APPROVE|REJECT", message = "审核决策只能为 APPROVE 或 REJECT")
        String decision,

        @Size(max = 500, message = "审核意见不能超过 500 字")
        String remark
) {
}
