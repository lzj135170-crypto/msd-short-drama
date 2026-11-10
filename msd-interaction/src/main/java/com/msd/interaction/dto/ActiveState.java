package com.msd.interaction.dto;

/**
 * 当前用户的点赞/收藏状态。
 *
 * @param active 是否处于已点赞或已收藏状态
 */
public record ActiveState(boolean active) {
}
