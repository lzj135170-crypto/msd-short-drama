package com.msd.drama.cache;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CacheEvictorTest {

    @Test
    void evictAfterCommitDeletesImmediatelyAndAgainAfterDelay() throws Exception {
        CacheProperties properties = new CacheProperties();
        CacheBackend backend = mock(CacheBackend.class);
        ThreadPoolTaskScheduler scheduler = scheduler();
        try {
            CacheEvictor evictor = new CacheEvictor(
                    properties, new L1CacheRegistry(properties), backend, scheduler,
                    mock(CacheEvictionBroadcaster.class));

            evictor.evictAfterCommit("detail", "5005", false, 20);

            verify(backend, times(1)).delete("msd:drama:cache:detail:5005");
            Thread.sleep(120);
            verify(backend, times(2)).delete("msd:drama:cache:detail:5005");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void detailDebounceCoalescesBursts() throws Exception {
        CacheProperties properties = new CacheProperties();
        properties.getEvict().setDetailDebounce(Duration.ofMillis(20));
        properties.getEvict().setDelay(Duration.ofMillis(20));
        CacheBackend backend = mock(CacheBackend.class);
        ThreadPoolTaskScheduler scheduler = scheduler();
        try {
            CacheEvictor evictor = new CacheEvictor(
                    properties, new L1CacheRegistry(properties), backend, scheduler,
                    mock(CacheEvictionBroadcaster.class));

            evictor.evictDetailDebounced(5005L);
            evictor.evictDetailDebounced(5005L);
            evictor.evictDetailDebounced(5005L);

            Thread.sleep(150);
            verify(backend, times(2)).delete("msd:drama:cache:detail:5005");
        } finally {
            scheduler.shutdown();
        }
    }

    private ThreadPoolTaskScheduler scheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("cache-test-");
        scheduler.initialize();
        return scheduler;
    }
}
