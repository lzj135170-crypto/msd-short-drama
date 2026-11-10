package com.msd.drama.cache.bloom;

import com.msd.drama.cache.CacheProperties;
import com.msd.drama.cache.RedissonClientAccessor;
import com.msd.drama.mapper.DramaMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class DefaultDramaBloomFilterServiceTest {

    @Test
    void rebuildAddsAllIdsBeforeSwitchingActivePointer() throws Exception {
        RedissonClientAccessor accessor = mock(RedissonClientAccessor.class);
        RedissonClient client = mock(RedissonClient.class);
        DramaMapper dramaMapper = mock(DramaMapper.class);
        RBloomFilter<Long> filter = mock(RBloomFilter.class);
        RBucket<String> active = mock(RBucket.class);
        RLock lock = mock(RLock.class);
        RKeys keys = mock(RKeys.class);

        when(accessor.get()).thenReturn(client);
        doReturn(filter).when(client).getBloomFilter(anyString());
        doReturn(active).when(client).getBucket(anyString(), any());
        when(client.getKeys()).thenReturn(keys);
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 5, TimeUnit.MINUTES)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(active.getAndSet(anyString())).thenReturn("old-filter");
        when(filter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(dramaMapper.countPublic()).thenReturn(2L);
        when(dramaMapper.selectPublicIds(0, 1000)).thenReturn(List.of(1L, 2L));

        DefaultDramaBloomFilterService service =
                new DefaultDramaBloomFilterService(accessor, dramaMapper, new CacheProperties());

        BloomRebuildResult result = service.rebuild();

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.filterKey()).isNotBlank();
        verify(filter).add(1L);
        verify(filter).add(2L);
        verify(active).getAndSet(result.filterKey());
        verify(lock).unlock();
    }

    @Test
    void failsOpenWhenRedissonUnavailable() {
        RedissonClientAccessor accessor = mock(RedissonClientAccessor.class);
        when(accessor.get()).thenReturn(null);
        DefaultDramaBloomFilterService service =
                new DefaultDramaBloomFilterService(accessor, mock(DramaMapper.class), new CacheProperties());

        assertThat(service.mightContain(5005L)).isTrue();
    }

    @Test
    void usesActiveFilterWhenPresent() {
        RedissonClientAccessor accessor = mock(RedissonClientAccessor.class);
        RedissonClient client = mock(RedissonClient.class);
        RBloomFilter<Long> filter = mock(RBloomFilter.class);
        RBucket<String> active = mock(RBucket.class);
        when(accessor.get()).thenReturn(client);
        doReturn(active).when(client).getBucket(anyString(), any());
        when(active.get()).thenReturn("msd:drama:bloom:v1");
        doReturn(filter).when(client).getBloomFilter("msd:drama:bloom:v1");
        when(filter.contains(5005L)).thenReturn(true);

        DefaultDramaBloomFilterService service =
                new DefaultDramaBloomFilterService(accessor, mock(DramaMapper.class), new CacheProperties());

        assertThat(service.mightContain(5005L)).isTrue();
        verify(filter).contains(5005L);
    }
}
