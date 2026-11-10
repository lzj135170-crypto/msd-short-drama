package com.msd.content.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创作者提交前的 AI 预检请求。
 *
 * <p>只包含待检查的文案和分集标题，不包含价格、视频等业务字段。</p>
 *
 * @param title        短剧标题
 * @param subtitle     副标题
 * @param description  简介
 * @param episodeTitles 分集标题列表
 */
public record DramaPrecheckRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 128, message = "标题不能超过 128 字")
        String title,

        @Size(max = 255, message = "副标题不能超过 255 字")
        String subtitle,

        @Size(max = 1000, message = "简介不能超过 1000 字")
        String description,

        @Size(max = 50, message = "分集标题不能超过 50 条")
        List<@Size(max = 128, message = "分集标题不能超过 128 字") String> episodeTitles
) {

    /**
     * 统一空值，避免调用方判空。
     */
    public DramaPrecheckRequest {
        subtitle = subtitle == null ? "" : subtitle;
        description = description == null ? "" : description;
        episodeTitles = episodeTitles == null ? List.of() : List.copyOf(episodeTitles);
    }
}
