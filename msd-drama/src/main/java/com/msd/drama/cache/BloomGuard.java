package com.msd.drama.cache;

/**
 * 布隆过滤器守卫；由后续布隆服务实现，缺失时缓存切面自动跳过。
 */
public interface BloomGuard {

    /**
     * 判断 ID 是否可能存在。
     */
    boolean mightContain(Long id);
}
