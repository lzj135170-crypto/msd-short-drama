package com.msd.common.event;

/**
 * 分集字幕变更事件，写入 msd.transcript.changed.v1。
 *
 * <p>content 消费后重建单集 transcript 索引；删除动作用于剧集下线或字幕重置。</p>
 *
 * @param eventId    事件 UUID
 * @param episodeId  剧集 ID
 * @param action     变更动作
 * @param occurredAt 事件发生时间，epoch millis
 */
public record TranscriptChangedEvent(
        String eventId,
        Long episodeId,
        DramaChangedAction action,
        long occurredAt
) {

    /**
     * 缺失动作时按 UPSERT 处理。
     */
    public TranscriptChangedEvent {
        action = action == null ? DramaChangedAction.UPSERT : action;
    }
}
