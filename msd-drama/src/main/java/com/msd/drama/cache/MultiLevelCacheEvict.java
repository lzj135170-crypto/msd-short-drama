package com.msd.drama.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 写方法缓存失效：默认写前删一次，事务提交后延迟再删一次。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(MultiLevelCacheEvicts.class)
public @interface MultiLevelCacheEvict {

    /**
     * 缓存命名空间，例如 home、detail。
     */
    String cacheName();

    /**
     * 缓存 key 的 SpEL 表达式；allEntries=true 时可忽略。
     */
    String key() default "";

    /**
     * 是否清空该命名空间下所有 key。
     */
    boolean allEntries() default false;

    /**
     * true 表示方法执行前删除第一次；false 表示事务提交后立即删除第一次。
     */
    boolean beforeInvocation() default true;

    /**
     * 第二次删除的延迟毫秒数；<=0 使用全局 msd.cache.evict.delay。
     */
    long delayMillis() default 500;
}
