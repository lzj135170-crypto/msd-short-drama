package com.msd.drama.cache;

/**
 * L1 缓存值；nullValue 显式区分“缓存了 null”和“未命中”。
 *
 * @param nullValue 是否缓存了 null 结果
 * @param value     业务值；nullValue 为 true 时为 null
 */
public record CachedValue(boolean nullValue, Object value) {

    /**
     * 按业务值是否为空创建缓存值。
     */
    public static CachedValue of(Object value) {
        return new CachedValue(value == null, value);
    }
}
