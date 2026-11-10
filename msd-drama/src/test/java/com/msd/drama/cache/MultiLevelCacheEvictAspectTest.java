package com.msd.drama.cache;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MultiLevelCacheEvictAspectTest {

    @Test
    void deletesBeforeInvocationAndAgainAfterDelay() throws Exception {
        CacheProperties properties = new CacheProperties();
        CacheBackend backend = mock(CacheBackend.class);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        try {
            CacheEvictor evictor = new CacheEvictor(
                    properties, new L1CacheRegistry(properties), backend, scheduler,
                    mock(CacheEvictionBroadcaster.class));
            MultiLevelCacheEvictAspect aspect = new MultiLevelCacheEvictAspect(
                    evictor, new CacheKeyResolver(), properties);
            EvictTarget target = new EvictTarget();
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(aspect);
            EvictTarget proxy = factory.getProxy();

            assertThat(proxy.update(5005L)).isEqualTo("updated");

            verify(backend, times(1)).delete("msd:drama:cache:detail:5005");
            Thread.sleep(120);
            verify(backend, times(2)).delete("msd:drama:cache:detail:5005");
            assertThat(target.calls).isEqualTo(1);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void handlesRepeatableEvictAnnotations() throws Exception {
        CacheProperties properties = new CacheProperties();
        CacheBackend backend = mock(CacheBackend.class);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        try {
            CacheEvictor evictor = new CacheEvictor(
                    properties, new L1CacheRegistry(properties), backend, scheduler,
                    mock(CacheEvictionBroadcaster.class));
            MultiLevelCacheEvictAspect aspect = new MultiLevelCacheEvictAspect(
                    evictor, new CacheKeyResolver(), properties);
            EvictTarget target = new EvictTarget();
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(aspect);
            EvictTarget proxy = factory.getProxy();

            assertThat(proxy.updateBoth(5005L)).isEqualTo("updated");

            verify(backend, times(1)).delete("msd:drama:cache:home:v1");
            verify(backend, times(1)).delete("msd:drama:cache:detail:5005");
        } finally {
            scheduler.shutdown();
        }
    }

    static class EvictTarget {

        private int calls;

        @MultiLevelCacheEvict(cacheName = "detail", key = "#id", delayMillis = 20)
        public String update(Long id) {
            calls++;
            return "updated";
        }

        @MultiLevelCacheEvict(cacheName = "home", key = "'v1'", delayMillis = 5000)
        @MultiLevelCacheEvict(cacheName = "detail", key = "#id", delayMillis = 5000)
        public String updateBoth(Long id) {
            calls++;
            return "updated";
        }
    }
}
