package com.msd.drama.cache;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiLevelCacheAspectTest {

    private static final BloomGuard BLOOM = id -> id != 3L;

    @Test
    void servesSecondCallFromL1() {
        Fixture fixture = new Fixture();
        Target proxy = fixture.proxy();

        assertThat(proxy.load(1L)).isEqualTo("one");
        assertThat(proxy.load(1L)).isEqualTo("one");

        assertThat(fixture.target.calls).isEqualTo(1);
    }

    @Test
    void servesAfterL1EvictionFromL2() {
        Fixture fixture = new Fixture();
        Target proxy = fixture.proxy();

        assertThat(proxy.load(1L)).isEqualTo("one");
        fixture.l1.cache("detail").invalidateAll();
        assertThat(proxy.load(1L)).isEqualTo("one");

        assertThat(fixture.target.calls).isEqualTo(1);
    }

    @Test
    void cachesNullValue() {
        Fixture fixture = new Fixture();
        Target proxy = fixture.proxy();

        assertThat(proxy.load(2L)).isNull();
        assertThat(proxy.load(2L)).isNull();

        assertThat(fixture.target.calls).isEqualTo(1);
    }

    @Test
    void bloomRejectsBeforeDatabase() {
        Fixture fixture = new Fixture();
        Target proxy = fixture.proxy();

        assertThat(proxy.loadBloom(3L)).isNull();

        assertThat(fixture.target.calls).isZero();
    }

    @Test
    void spinsWhileAnotherThreadHoldsLockThenLoads() {
        Fixture fixture = new Fixture();
        fixture.backend.busyBeforeAcquire = 2;
        Target proxy = fixture.proxy();

        assertThat(proxy.load(1L)).isEqualTo("one");

        assertThat(fixture.target.calls).isEqualTo(1);
        assertThat(fixture.backend.attempts).isGreaterThanOrEqualTo(3);
    }

    private static final class Fixture {

        private final Target target = new Target();
        private final CacheProperties properties = new CacheProperties();
        private final L1CacheRegistry l1 = new L1CacheRegistry(properties);
        private final InMemoryCacheBackend backend = new InMemoryCacheBackend();
        private final CacheSerializer serializer = new CacheSerializer(JsonMapper.builder().build());

        private Fixture() {
            properties.getLock().setWait(Duration.ofMillis(150));
            properties.getLock().setSpin(Duration.ofMillis(1));
        }

        @SuppressWarnings("unchecked")
        private Target proxy() {
            ObjectProvider<BloomGuard> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(BLOOM);
            MultiLevelCacheAspect aspect = new MultiLevelCacheAspect(
                    properties, serializer, l1, backend, new CacheKeyResolver(), provider);
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(aspect);
            return factory.getProxy();
        }
    }

    static class Target {

        private int calls;

        @MultiLevelCache(cacheName = "test", key = "#id", policy = "detail", cacheNull = true)
        public String load(Long id) {
            calls++;
            return id == 1L ? "one" : null;
        }

        @MultiLevelCache(cacheName = "bloom", key = "#id", policy = "detail",
                bloomId = "#id", cacheNull = true)
        public String loadBloom(Long id) {
            calls++;
            return "hit";
        }
    }

    private static final class InMemoryCacheBackend implements CacheBackend {

        private final Map<String, String> data = new ConcurrentHashMap<>();
        private int busyBeforeAcquire;
        private int attempts;

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            data.put(key, value);
        }

        @Override
        public void delete(String key) {
            data.remove(key);
        }

        @Override
        public void deleteByPattern(String pattern) {
            String prefix = pattern.endsWith("*") ? pattern.substring(0, pattern.length() - 1) : pattern;
            data.keySet().removeIf(key -> key.startsWith(prefix));
        }

        @Override
        public LockHandle tryLock(String key, Duration wait, Duration lease) {
            attempts++;
            if (busyBeforeAcquire > 0) {
                busyBeforeAcquire--;
                return LockHandle.busy();
            }
            return new LockHandle(LockAttempt.ACQUIRED, null);
        }

        @Override
        public void unlock(LockHandle handle) {
            // 测试不持有真实 Redisson 锁
        }
    }
}
