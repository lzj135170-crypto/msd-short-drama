package com.msd.drama.cache;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class RedissonClientAccessorTest {

    @Test
    void initializesClientInBackground() throws Exception {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        RedissonClient client = mock(RedissonClient.class);
        when(provider.getObject()).thenReturn(client);
        RedissonClientAccessor accessor =
                new RedissonClientAccessor(provider, new CacheProperties());
        try {
            accessor.start();
            await(() -> accessor.get() != null);
            assertThat(accessor.get()).isSameAs(client);
        } finally {
            accessor.shutdown();
        }
    }

    @Test
    void failsOpenAndRecordsErrorWhenInitFails() throws Exception {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenThrow(new IllegalStateException("redis down"));
        RedissonClientAccessor accessor =
                new RedissonClientAccessor(provider, new CacheProperties());
        try {
            accessor.start();
            await(() -> accessor.getLastError() != null);
            assertThat(accessor.get()).isNull();
            assertThat(accessor.getLastError()).contains("redis down");
        } finally {
            accessor.shutdown();
        }
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }
}
