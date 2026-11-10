package com.msd.content.copilot;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 模型返回的创作者 Copilot 建议。
 *
 * <p>字段名与模型 JSON 对齐；所有文案只作为参考，不会自动写回短剧。</p>
 */
public record CreatorCopilotSuggestion(
        @JsonProperty("versions")
        @JsonPropertyDescription("2-3 个文案版本")
        List<CreatorCopilotVersion> versions,

        @JsonProperty("tips")
        @JsonPropertyDescription("最多 3 条合规或改写提醒")
        List<String> tips
) {

    /**
     * 统一空值。
     */
    public CreatorCopilotSuggestion {
        versions = versions == null ? List.of() : List.copyOf(versions);
        tips = tips == null ? List.of() : List.copyOf(tips);
    }
}
