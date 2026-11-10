package com.msd.content.copilot;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创作者 Copilot 生成请求。
 *
 * @param title        当前标题，可为空
 * @param subtitle     当前副标题，可为空
 * @param description  剧情简介，标题和简介至少填一个
 * @param actors       主演，可为空
 * @param episodeTitles 分集标题，可为空
 * @param tone         期望风格，例如甜宠、悬疑、爽感
 * @param count        生成版本数 1-3
 */
public record CreatorCopilotRequest(
        @Size(max = 128) String title,
        @Size(max = 255) String subtitle,
        @Size(max = 1000) String description,
        @Size(max = 255) String actors,
        @Size(max = 50) List<@Size(max = 100) String> episodeTitles,
        @Size(max = 20) String tone,
        @Min(1) @Max(3) Integer count
) {

    /**
     * 统一空值和默认版本数。
     */
    public CreatorCopilotRequest {
        title = title == null ? "" : title.trim();
        subtitle = subtitle == null ? "" : subtitle.trim();
        description = description == null ? "" : description.trim();
        actors = actors == null ? "" : actors.trim();
        episodeTitles = episodeTitles == null ? List.of() : List.copyOf(episodeTitles);
        tone = tone == null ? "" : tone.trim();
        count = count == null ? 3 : count;
    }
}
