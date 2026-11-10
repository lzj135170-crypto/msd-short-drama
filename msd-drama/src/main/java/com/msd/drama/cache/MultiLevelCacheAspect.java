package com.msd.drama.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * 多级缓存切面：L1 -> L2 -> 布隆 -> 分布式锁 -> DB。
 *
 * <p>缓存组件异常一律降级到原方法，不能让缓存故障阻断首页和详情。</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MultiLevelCacheAspect {

    // 缓存命中与降级日志
    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheAspect.class);

    // 缓存配置
    private final CacheProperties properties;
    // L2 值编解码器
    private final CacheSerializer serializer;
    // 本地 L1 缓存注册表
    private final L1CacheRegistry l1Registry;
    // Redis L2 缓存与分布式锁
    private final CacheBackend backend;
    // 注解 SpEL key 解析器
    private final CacheKeyResolver keyResolver;
    // 布隆守卫，可能不存在；不存在时跳过布隆过滤
    private final ObjectProvider<BloomGuard> bloomGuardProvider;

    /**
     * 注入缓存配置、序列化器、L1 注册表、Redis 后端、key 解析器和布隆守卫。
     */
    public MultiLevelCacheAspect(CacheProperties properties,
                                 CacheSerializer serializer,
                                 L1CacheRegistry l1Registry,
                                 CacheBackend backend,
                                 CacheKeyResolver keyResolver,
                                 ObjectProvider<BloomGuard> bloomGuardProvider) {
        this.properties = properties;
        this.serializer = serializer;
        this.l1Registry = l1Registry;
        this.backend = backend;
        this.keyResolver = keyResolver;
        this.bloomGuardProvider = bloomGuardProvider;
    }

    /**
     * 读路径：命中直接返回；未命中时用 Redisson 锁保证单飞重建。
     */
    @Around("@annotation(cache)")
    public Object around(ProceedingJoinPoint joinPoint, MultiLevelCache cache) throws Throwable {
        if (!properties.isEnabled() || !cache.enabled()) {
            return joinPoint.proceed();
        }

        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String key = keyResolver.resolveKey(cache.key(), method, joinPoint.getArgs());
        String cacheName = cache.cacheName();
        String redisKey = CacheKeys.data(cacheName, key);
        String localKey = CacheKeys.local(cacheName, key);
        CacheProperties.Policy policy = properties.policy(cache.policy());
        Cache<String, CachedValue> local = l1Registry.cache(cache.policy());

        // 1. L1 命中不访问 Redis
        CachedValue localValue = local.getIfPresent(localKey);
        if (localValue != null) {
            log.debug("cache hit l1: {}", localKey);
            return localValue.value();
        }

        // 2. L2 命中回填 L1
        CachedValue l2Value = readL2(redisKey, method);
        if (l2Value != null) {
            local.put(localKey, l2Value);
            log.debug("cache hit l2: {}", redisKey);
            return l2Value.value();
        }

        // 3. 布隆过滤仅在 L1/L2 都 miss 后执行，避免缓存命中时多一次 Redis 往返
        if (!cache.bloomId().isBlank()) {
            Long bloomId = keyResolver.resolveLong(cache.bloomId(), method, joinPoint.getArgs());
            BloomGuard guard = bloomGuardProvider.getIfAvailable();
            if (guard != null && bloomId != null) {
                try {
                    if (!guard.mightContain(bloomId)) {
                        log.debug("bloom rejected id={}, cache={}", bloomId, cacheName);
                        return null;
                    }
                } catch (Exception e) {
                    log.warn("bloom check failed, fail-open: {}", e.getMessage());
                }
            }
        }

        // 4. 不需要锁时直接查库并回填
        if (!cache.lock()) {
            Object value = joinPoint.proceed();
            cacheValue(local, localKey, redisKey, value, policy, cache.cacheNull());
            return value;
        }

        // 5. 分布式锁 + 自旋等待
        long deadline = System.nanoTime() + properties.getLock().getWait().toNanos();
        while (true) {
            LockHandle handle = backend.tryLock(
                    CacheKeys.lock(cacheName, key), Duration.ZERO, properties.getLock().getLease());
            if (handle.attempt() == LockAttempt.ACQUIRED) {
                try {
                    // 5.1 拿到锁后二次检查，可能其他实例已经回填
                    CachedValue second = readL2(redisKey, method);
                    if (second != null) {
                        local.put(localKey, second);
                        return second.value();
                    }
                    Object value = joinPoint.proceed();
                    cacheValue(local, localKey, redisKey, value, policy, cache.cacheNull());
                    return value;
                } finally {
                    backend.unlock(handle);
                }
            }

            // 5.2 Redis 完全不可用：直接查库，避免请求被缓存故障挂住
            if (handle.attempt() == LockAttempt.UNAVAILABLE) {
                Object value = joinPoint.proceed();
                cacheValue(local, localKey, redisKey, value, policy, cache.cacheNull());
                return value;
            }

            // 5.3 其他线程持锁：自旋等待，期间不断重试 L1/L2
            localValue = local.getIfPresent(localKey);
            if (localValue != null) {
                return localValue.value();
            }
            CachedValue waited = readL2(redisKey, method);
            if (waited != null) {
                local.put(localKey, waited);
                return waited.value();
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            LockSupport.parkNanos(properties.getLock().getSpin().toNanos());
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // 6. 等锁超时：直接查库但不回填，避免多实例同时惊群写缓存
        log.warn("cache rebuild wait timeout: {}", redisKey);
        return joinPoint.proceed();
    }

    /**
     * 读 L2 并解码；损坏或版本不匹配时删除脏 key。
     */
    private CachedValue readL2(String redisKey, Method method) {
        Optional<String> raw = backend.get(redisKey);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            DecodedCache decoded = serializer.decode(raw.get(), method.getGenericReturnType());
            return new CachedValue(decoded.nullValue(), decoded.value());
        } catch (CacheSerializationException e) {
            log.warn("drop corrupt cache key {}: {}", redisKey, e.getMessage());
            backend.delete(redisKey);
            return null;
        }
    }

    /**
     * 写入 L1/L2；null 是否缓存由注解决定。
     */
    private void cacheValue(Cache<String, CachedValue> local, String localKey, String redisKey,
                            Object value, CacheProperties.Policy policy, boolean cacheNull) {
        if (value == null && !cacheNull) {
            return;
        }
        CachedValue cached = CachedValue.of(value);
        local.put(localKey, cached);
        Duration ttl = value == null ? policy.getNullTtl() : policy.getL2Ttl();
        try {
            backend.set(redisKey, serializer.encode(value), jitter(ttl));
        } catch (Exception e) {
            log.warn("write l2 cache failed: {}: {}", redisKey, e.getMessage());
        }
    }

    /**
     * TTL 加 ±10% 抖动，避免同一时刻大量 key 失效。
     */
    private Duration jitter(Duration ttl) {
        long millis = Math.max(1_000L, ttl.toMillis());
        long delta = Math.max(1L, Math.round(millis * 0.1D));
        long jittered = millis + ThreadLocalRandom.current().nextLong(-delta, delta + 1);
        return Duration.ofMillis(Math.max(1_000L, jittered));
    }

}
