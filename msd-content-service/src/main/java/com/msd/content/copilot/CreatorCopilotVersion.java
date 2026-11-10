package com.msd.content.copilot;

import java.util.List;

/**
 * 一个可人工采纳的文案版本。
 *
 * @param title     标题
 * @param subtitle  副标题
 * @param tags      标签建议
 * @param angle     文案角度，例如冲突前置、身份悬念
 * @param rationale 推荐理由
 */
public record CreatorCopilotVersion(
        String title,
        String subtitle,
        List<String> tags,
        String angle,
        String rationale
) {

    /**
     * 统一空值。
     */
    public CreatorCopilotVersion {
        title = title == null ? "" : title;
        subtitle = subtitle == null ? "" : subtitle;
        tags = tags == null ? List.of() : List.copyOf(tags);
        angle = angle == null ? "" : angle;
        rationale = rationale == null ? "" : rationale;
    }
}
