package com.msd.common.event;

/**
 * Kafka Topic 常量，与 scripts/create-topics.cmd 中的 topic 保持一致。
 */
public final class KafkaTopics {

    /**
     * 工具类禁止实例化。
     */
    private KafkaTopics() {
    }

    /** 点赞/取消点赞事件。 */
    public static final String INTERACTION_LIKE = "msd.interaction.like.v1";

    /** 收藏/取消收藏事件。 */
    public static final String INTERACTION_FAVORITE = "msd.interaction.favorite.v1";

    /** 评论创建/删除事件。 */
    public static final String INTERACTION_COMMENT = "msd.interaction.comment.v1";

    /** 首次观看事件。 */
    public static final String INTERACTION_HISTORY = "msd.interaction.history.v1";

    /** 短剧上架/下架/内容变更事件，驱动 RAG 增量索引。 */
    public static final String DRAMA_CHANGED = "msd.drama.changed.v1";

    /** 分集字幕/ASR 变更事件，驱动 transcript 索引。 */
    public static final String TRANSCRIPT_CHANGED = "msd.transcript.changed.v1";

    /** 互动事件死信队列。 */
    public static final String INTERACTION_DLQ = "msd.interaction.dlq.v1";
}
