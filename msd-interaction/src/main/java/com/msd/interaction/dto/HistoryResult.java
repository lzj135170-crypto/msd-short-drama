package com.msd.interaction.dto;

/**
 * 观看进度上报结果。
 *
 * @param firstWatch 是否首次观看
 * @param eventId    首次观看产生的事件 ID；非首次为空
 */
public record HistoryResult(boolean firstWatch, String eventId) {
}
