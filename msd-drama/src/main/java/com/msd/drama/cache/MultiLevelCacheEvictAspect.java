package com.msd.drama.cache;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 写方法缓存失效切面：写前删除 + 事务提交后延迟再删；或提交后立即删 + 延迟再删。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MultiLevelCacheEvictAspect {

    // 缓存失效执行器
    private final CacheEvictor evictor;
    // 注解 SpEL key 解析器
    private final CacheKeyResolver keyResolver;
    // 缓存配置，提供默认延迟
    private final CacheProperties properties;

    /**
     * 注入失效执行器、key 解析器和缓存配置。
     */
    public MultiLevelCacheEvictAspect(CacheEvictor evictor,
                                      CacheKeyResolver keyResolver,
                                      CacheProperties properties) {
        this.evictor = evictor;
        this.keyResolver = keyResolver;
        this.properties = properties;
    }

    /**
     * 环绕写方法：方法执行前删除一次，提交后再延迟删除；执行异常时不安排第二次删除。
     */
    @Around("@annotation(com.msd.drama.cache.MultiLevelCacheEvict) "
            + "|| @annotation(com.msd.drama.cache.MultiLevelCacheEvicts)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        MultiLevelCacheEvict[] annotations = method.getAnnotationsByType(MultiLevelCacheEvict.class);
        if (annotations.length == 0) {
            return joinPoint.proceed();
        }

        // 1. 写前删除第一次
        for (MultiLevelCacheEvict annotation : annotations) {
            if (annotation.beforeInvocation()) {
                evictor.evictNow(annotation.cacheName(), key(annotation, method, joinPoint.getArgs()),
                        annotation.allEntries());
            }
        }

        // 2. 执行写操作
        Object result = joinPoint.proceed();

        // 3. 事务提交后安排第二次删除
        for (MultiLevelCacheEvict annotation : annotations) {
            String cacheKey = key(annotation, method, joinPoint.getArgs());
            long delay = annotation.delayMillis() > 0
                    ? annotation.delayMillis()
                    : properties.getEvict().getDelay().toMillis();
            if (annotation.beforeInvocation()) {
                evictor.scheduleDelayedAfterCommit(annotation.cacheName(), cacheKey,
                        annotation.allEntries(), delay);
            } else {
                evictor.evictAfterCommit(annotation.cacheName(), cacheKey,
                        annotation.allEntries(), delay);
            }
        }
        return result;
    }

    /**
     * 解析失效 key；allEntries 时返回空字符串，忽略 SpEL。
     */
    private String key(MultiLevelCacheEvict annotation, Method method, Object[] args) {
        return annotation.allEntries() ? "" : keyResolver.resolveKey(annotation.key(), method, args);
    }
}
