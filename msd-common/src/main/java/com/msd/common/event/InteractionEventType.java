package com.msd.common.event;

/**
 * 互动事件类型，和 t_outbox_event.event_type 的取值对应。
 */
public enum InteractionEventType {

    /** 点赞。 */
    LIKE,

    /** 取消点赞。 */
    UNLIKE,

    /** 收藏。 */
    FAVORITE,

    /** 取消收藏。 */
    UNFAVORITE,

    /** 创建评论。 */
    COMMENT,

    /** 删除评论。 */
    COMMENT_DELETED,

    /** 首次观看。 */
    HISTORY
}
