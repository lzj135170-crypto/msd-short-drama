package com.msd.drama.cache;

/**
 * 反序列化后的缓存值。
 *
 * @param nullValue 是否缓存了 null 结果
 * @param value     业务值；nullValue 为 true 时为 null
 */
public record DecodedCache(boolean nullValue, Object value) {
}
