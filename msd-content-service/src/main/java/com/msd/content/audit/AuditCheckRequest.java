package com.msd.content.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 结构化审核请求。
 *
 * @param content 待审核文本，1-5000 字
 * @param scene   业务场景，如 drama_description、comment，可为空
 */
public record AuditCheckRequest(
        @NotBlank @Size(max = 5000) String content,
        @Size(max = 64) String scene
) {
}
