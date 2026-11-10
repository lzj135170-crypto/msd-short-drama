package com.msd.content.index;

import com.msd.content.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * eventId 幂等键的首次、重复和 Redis 故障测试。
 */
class IndexEventDeduplicatorTest {

    @SuppressWarnings("unchecked")
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBucket<String> bucket = mock(RBucket.class);
    private final RagProperties properties = new RagProperties();

    private final IndexEventDeduplicator deduplicator =
            new IndexEventDeduplicator(redissonClient, properties);

    /**
     * 首次写入返回 true。
     */
    @Test
    void acceptsFirstEvent() {
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);

        assertThat(deduplicator.markIfNew("event-1")).isTrue();
    }

    /**
     * 已存在的 eventId 返回 false。
     */
    @Test
    void rejectsDuplicateEvent() {
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(false);

        assertThat(deduplicator.markIfNew("event-1")).isFalse();
    }

    /**
     * Redis 不可用时放行，避免索引停摆。
     */
    @Test
    void failsOpenWhenRedisUnavailable() {
        when(redissonClient.getBucket(anyString())).thenThrow(new IllegalStateException("down"));

        assertThat(deduplicator.markIfNew("event-1")).isTrue();
        assertThat(deduplicator.isProcessed("event-1")).isFalse();
    }

    /**
     * 已存在的幂等键返回 true。
     */
    @Test
    void detectsProcessedEvent() {
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(true);

        assertThat(deduplicator.isProcessed("event-1")).isTrue();
    }
}
