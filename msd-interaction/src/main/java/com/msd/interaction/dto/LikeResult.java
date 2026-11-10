package com.msd.interaction.dto;

/**
 * 点赞操作结果。
 *
 * @param active  当前是否处于已点赞状态
 * @param eventId 本次产生的事件 ID；状态未变化时为空
 */
public record LikeResult(boolean active, String eventId) {
}
