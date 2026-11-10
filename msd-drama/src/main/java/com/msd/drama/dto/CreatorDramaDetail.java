package com.msd.drama.dto;

import com.msd.common.dto.DramaReviewCandidateDTO;

import java.util.List;

/**
 * 创作者短剧详情，包含基础信息和剧集列表。
 *
 * @param drama    短剧信息
 * @param episodes 剧集列表
 */
public record CreatorDramaDetail(
        DramaReviewCandidateDTO drama,
        List<CreatorEpisodeView> episodes
) {

    /**
     * 统一空列表。
     */
    public CreatorDramaDetail {
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
    }
}
