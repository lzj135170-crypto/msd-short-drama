package com.msd.interaction.dto;

/**
 * 当前用户对一部短剧的点赞和收藏状态。
 *
 * @param dramaId   短剧 ID
 * @param liked     是否已点赞
 * @param favorited 是否已收藏
 */
public record DramaState(
        Long dramaId,
        boolean liked,
        boolean favorited
) {
}
