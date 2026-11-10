package com.msd.content.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 模型返回的创作者预检文案建议。
 *
 * <p>字段名与模型 JSON 对齐；所有建议都只是参考，不会自动写回短剧。</p>
 */
public record DramaPrecheckSuggestion(
        @JsonProperty("title_suggestions")
        @JsonPropertyDescription("2-3 条标题建议")
        List<String> titleSuggestions,

        @JsonProperty("subtitle_suggestions")
        @JsonPropertyDescription("2-3 条副标题建议")
        List<String> subtitleSuggestions,

        @JsonProperty("tag_suggestions")
        @JsonPropertyDescription("最多 5 个标签建议")
        List<String> tagSuggestions,

        @JsonProperty("tips")
        @JsonPropertyDescription("最多 3 条整改建议")
        List<String> tips
) {

    /**
     * 统一空值，避免调用方判空。
     */
    public DramaPrecheckSuggestion {
        titleSuggestions = titleSuggestions == null ? List.of() : List.copyOf(titleSuggestions);
        subtitleSuggestions = subtitleSuggestions == null
                ? List.of() : List.copyOf(subtitleSuggestions);
        tagSuggestions = tagSuggestions == null ? List.of() : List.copyOf(tagSuggestions);
        tips = tips == null ? List.of() : List.copyOf(tips);
    }
}
