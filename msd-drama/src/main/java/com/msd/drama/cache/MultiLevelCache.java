package com.msd.drama.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多级缓存注解：L1 Caffeine、L2 Redis，可选布隆过滤和击穿锁。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MultiLevelCache {

    /**
     * 缓存命名空间，例如 home、detail。
     */
    String cacheName();

    /**
     * 缓存 key 的 SpEL 表达式；无参方法可写字符串字面量，例如 "'v1'"。
     */
    String key();

    /**
     * 缓存策略名，对应 msd.cache.policies.*。
     */
    String policy() default "default";

    /**
     * 是否缓存 null 结果，防止空值反复穿透。
     */
    boolean cacheNull() default true;

    /**
     * 布隆过滤 ID 的 SpEL 表达式；为空表示不启用。
     */
    String bloomId() default "";

    /**
     * 是否使用 Redisson 分布式锁防止击穿。
     */
    boolean lock() default true;

    /**
     * 是否启用该缓存。
     */
    boolean enabled() default true;
}
