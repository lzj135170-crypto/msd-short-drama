package com.msd.drama.cache.bloom;

/**
 * 布隆过滤器重建结果。
 *
 * @param total        数据库中公共短剧总数
 * @param added        实际写入过滤器的 ID 数量
 * @param filterKey    新过滤器的 Redis key
 * @param oldFilterKey 被替换的旧过滤器 key；首次重建时为 null
 * @param elapsedMs    重建耗时，单位毫秒
 */
public record BloomRebuildResult(
        long total,
        long added,
        String filterKey,
        String oldFilterKey,
        long elapsedMs
) {
}
