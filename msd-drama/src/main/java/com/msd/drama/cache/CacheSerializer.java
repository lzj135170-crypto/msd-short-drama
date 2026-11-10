package com.msd.drama.cache;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 缓存值的 JSON 编解码；显式处理 null 和版本不匹配。
 */
@Component
public class CacheSerializer {

    /** 缓存结构版本号，序列化格式变更时递增 */
    public static final int VERSION = 1;

    // Jackson JSON 编解码器
    private final JsonMapper jsonMapper;
    // 方法返回类型到 Jackson JavaType 的缓存，避免重复构造
    private final Map<Type, JavaType> javaTypes = new ConcurrentHashMap<>();

    /**
     * 注入 JSON 编解码器。
     */
    public CacheSerializer(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 把返回值编码成带版本和 null 标记的 JSON。
     */
    public String encode(Object value) {
        try {
            // 1. 业务值为 null 时不写 data，仅依赖 nullValue 标记
            String data = value == null ? null : jsonMapper.writeValueAsString(value);
            // 2. 再包一层版本和 null 标记，便于读取时区分未命中
            return jsonMapper.writeValueAsString(new CacheEnvelope(VERSION, value == null, data));
        } catch (Exception e) {
            throw new CacheSerializationException("缓存值序列化失败", e);
        }
    }

    /**
     * 按方法返回类型反序列化；版本不匹配或 JSON 损坏时抛出异常，由切面删除脏 key。
     */
    public DecodedCache decode(String raw, Type returnType) {
        try {
            // 1. 解析外层包装并校验版本
            CacheEnvelope envelope = jsonMapper.readValue(raw, CacheEnvelope.class);
            if (envelope.version() != VERSION) {
                throw new CacheSerializationException("缓存版本不匹配: " + envelope.version());
            }
            // 2. 缓存的是 null 时直接返回空值标记
            if (envelope.nullValue()) {
                return new DecodedCache(true, null);
            }
            if (envelope.data() == null) {
                throw new CacheSerializationException("缓存数据为空");
            }
            // 3. 按方法返回类型反序列化业务值
            JavaType javaType = javaTypes.computeIfAbsent(returnType, jsonMapper::constructType);
            Object value = jsonMapper.readValue(envelope.data(), javaType);
            return new DecodedCache(false, value);
        } catch (CacheSerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new CacheSerializationException("缓存值反序列化失败", e);
        }
    }
}
