package com.msd.drama.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多个 @MultiLevelCacheEvict 的容器。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MultiLevelCacheEvicts {

    /**
     * 同一方法上声明的多个失效规则。
     */
    MultiLevelCacheEvict[] value();
}
