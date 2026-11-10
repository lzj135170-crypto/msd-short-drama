package com.msd.common.event;

/**
 * 互动事件信封，写入 t_outbox_event.payload，由 Kafka 消费者反序列化。
 *
 * @param eventId    事件 UUID，消费端幂等键
 * @param eventType  事件类型
 * @param userId     触发事件的用户 ID
 * @param targetType 点赞目标类型：1 短剧，2 剧集，3 评论
 * @param targetId   点赞目标 ID
 * @param dramaId    短剧 ID，评论/收藏事件必填
 * @param episodeId  剧集 ID，剧集点赞/观看事件必填
 * @param commentId  评论 ID，评论事件必填
 * @param rootId     根评论 ID，回复事件使用
 * @param parentId   直接父评论 ID，回复事件使用
 * @param firstWatch 是否首次观看，仅 HISTORY 事件使用
 * @param occurredAt 事件发生时间，epoch millis
 */
public record InteractionEvent(
        String eventId,
        InteractionEventType eventType,
        long userId,
        Integer targetType,
        Long targetId,
        Long dramaId,
        Long episodeId,
        Long commentId,
        Long rootId,
        Long parentId,
        Boolean firstWatch,
        long occurredAt
) {
}
