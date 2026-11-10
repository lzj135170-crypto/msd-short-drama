package com.msd.interaction.dto;

/**
 * 推荐事件写入结果。
 *
 * @param recorded 是否记录成功；重复事件也返回 true
 */
public record RecommendationEventResult(boolean recorded) {
}
