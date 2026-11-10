package com.msd.drama.cache;

/**
 * 缓存值序列化或反序列化失败。
 */
public class CacheSerializationException extends RuntimeException {

    /**
     * 包装底层编解码异常，保留根因。
     */
    public CacheSerializationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 只带错误信息的构造器。
     */
    public CacheSerializationException(String message) {
        super(message);
    }
}
