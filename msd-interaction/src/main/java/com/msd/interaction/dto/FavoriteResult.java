package com.msd.interaction.dto;

/**
 * 收藏操作结果。
 *
 * @param active  当前是否处于已收藏状态
 * @param eventId 本次产生的事件 ID；状态未变化时为空
 */
public record FavoriteResult(boolean active, String eventId) {
}
