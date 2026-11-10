package com.msd.common.dto;

/**
 * 短剧审核候选或审核队列项。
 *
 * <p>content-service 通过内部接口读取待审短剧并写回 AI 结果；管理端用它展示审核队列。
 * 时间字段使用字符串，避免跨服务 Jackson 时间格式差异。</p>
 */
public record DramaReviewCandidateDTO(
        Long dramaId,
        String title,
        String subtitle,
        String description,
        String coverUrl,
        String actors,
        Long authorId,
        Long categoryId,
        Integer episodeCount,
        Integer reviewStatus,
        Integer aiReviewStatus,
        String aiReviewResult,
        String submitTime,
        String aiReviewTime,
        String reviewTime,
        String publishTime
) {

    /**
     * 统一空字符串，避免调用方判空。
     */
    public DramaReviewCandidateDTO {
        title = title == null ? "" : title;
        subtitle = subtitle == null ? "" : subtitle;
        description = description == null ? "" : description;
        coverUrl = coverUrl == null ? "" : coverUrl;
        actors = actors == null ? "" : actors;
        aiReviewResult = aiReviewResult == null ? "" : aiReviewResult;
    }
}
