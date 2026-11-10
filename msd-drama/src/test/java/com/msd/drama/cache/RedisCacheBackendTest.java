package com.msd.drama.cache;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisCacheBackendTest {

    @Test
    void degradesWhenClientNotReady() {
        RedissonClientAccessor accessor = mock(RedissonClientAccessor.class);
        when(accessor.get()).thenReturn(null);
        RedisCacheBackend backend = new RedisCacheBackend(accessor, new CacheProperties());

        assertThat(backend.get("k")).isEmpty();
        backend.set("k", "v", Duration.ofSeconds(1));
        backend.delete("k");
        assertThat(backend.tryLock("lock", Duration.ZERO, Duration.ofSeconds(1)).attempt())
                .isEqualTo(LockAttempt.UNAVAILABLE);
    }

    @Test
    void exposesAccessorErrorWhenNoOperationError() {
        RedissonClientAccessor accessor = mock(RedissonClientAccessor.class);
        when(accessor.get()).thenReturn(mock(RedissonClient.class));
        when(accessor.getLastError()).thenReturn("redis down");
        RedisCacheBackend backend = new RedisCacheBackend(accessor, new CacheProperties());

        assertThat(backend.getLastError()).isEqualTo("redis down");
    }
}
