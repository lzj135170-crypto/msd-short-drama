package com.msd.drama.dto;

import java.time.LocalDateTime;

/**
 * 审核操作后的短剧状态。
 *
 * @param dramaId        短剧 ID
 * @param reviewStatus   人工审核状态
 * @param aiReviewStatus AI 审核状态
 * @param publishTime    上架时间
 * @param reviewTime     人工审核时间
 */
public record DramaReviewState(
        Long dramaId,
        Integer reviewStatus,
        Integer aiReviewStatus,
        LocalDateTime publishTime,
        LocalDateTime reviewTime
) {
}
