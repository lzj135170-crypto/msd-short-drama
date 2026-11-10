package com.msd.agent.routing;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 路由缓存 Redis 故障 fail-open 测试。
 */
class RouteCacheTest {

    /**
     * Redisson Bean 创建失败（Redis 完全不可达）时同样 fail-open。
     */
    @Test
    void failsOpenWhenBeanCreationFails() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenThrow(new IllegalStateException("redis down"));
        RouteCache cache = new RouteCache(provider, new RouteProperties());

        assertThat(cache.get("消息")).isNull();
        assertThat(cache.get("消息2")).isNull();

        verify(provider, times(1)).getIfAvailable();
    }

    /**
     * Redis 读取异常时返回未命中，并进入退避期，不把异常抛给路由。
     */
    @Test
    void failsOpenAndBacksOff() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(anyString());
        when(bucket.get()).thenThrow(new IllegalStateException("redis down"));
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        RouteCache cache = new RouteCache(provider, new RouteProperties());

        assertThat(cache.get("第一次")).isNull();
        assertThat(cache.get("第二次")).isNull();

        // 第二次直接跳过 Redis，不重复等待连接超时
        verify(client, times(1)).getBucket(anyString());
    }
}
