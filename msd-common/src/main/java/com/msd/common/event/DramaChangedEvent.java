package com.msd.common.event;

/**
 * 短剧索引变更事件，写入 msd.drama.changed.v1。
 *
 * <p>content 消费后按 dramaId 增量更新 Milvus/ES；eventId 用于消费幂等，
 * occurredAt 用于计算索引延迟。</p>
 *
 * @param eventId    事件 UUID
 * @param dramaId    短剧 ID
 * @param action     变更动作
 * @param occurredAt 事件发生时间，epoch millis
 */
public record DramaChangedEvent(
        String eventId,
        Long dramaId,
        DramaChangedAction action,
        long occurredAt
) {

    /**
     * 缺失动作时按 UPSERT 处理，消费端会再次校验。
     */
    public DramaChangedEvent {
        action = action == null ? DramaChangedAction.UPSERT : action;
    }
}
