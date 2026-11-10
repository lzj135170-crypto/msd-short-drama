package com.msd.drama.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创作者创建或编辑短剧的请求。
 *
 * @param title       标题
 * @param subtitle    副标题
 * @param coverUrl    封面 URL
 * @param description 简介
 * @param categoryId  分类 ID
 * @param actors      主演，逗号分隔
 * @param episodes    剧集列表
 */
public record DramaWriteRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 128, message = "标题不能超过 128 字")
        String title,

        @Size(max = 255, message = "副标题不能超过 255 字")
        String subtitle,

        @Size(max = 512, message = "封面地址不能超过 512 字")
        String coverUrl,

        @Size(max = 1000, message = "简介不能超过 1000 字")
        String description,

        @NotNull(message = "分类不能为空")
        @Positive(message = "分类不合法")
        Long categoryId,

        @Size(max = 255, message = "主演不能超过 255 字")
        String actors,

        @NotEmpty(message = "至少需要一集")
        @Size(max = 200, message = "一次最多提交 200 集")
        List<@Valid EpisodeWriteItem> episodes
) {
}
