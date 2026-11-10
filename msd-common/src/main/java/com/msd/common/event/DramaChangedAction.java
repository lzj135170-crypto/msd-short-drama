package com.msd.common.event;

/**
 * 短剧索引变更动作。
 */
public enum DramaChangedAction {

    /** 上架或内容变更，需要重建单剧索引。 */
    UPSERT,

    /** 下架或删除，需要移除单剧索引。 */
    DELETE
}
