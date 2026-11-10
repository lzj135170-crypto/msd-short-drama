package com.msd.drama.cache;

/**
 * Redis 中的 JSON 包装，便于区分 null 结果和反序列化失败。
 *
 * @param version   缓存结构版本号，用于识别不兼容的旧数据
 * @param nullValue 是否缓存了 null 结果
 * @param data      业务值的 JSON 字符串；nullValue 为 true 时为空
 */
public record CacheEnvelope(int version, boolean nullValue, String data) {
}
