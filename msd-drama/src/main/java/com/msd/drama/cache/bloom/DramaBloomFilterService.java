package com.msd.drama.cache.bloom;

import com.msd.drama.cache.BloomGuard;

/**
 * 短剧布隆过滤器服务。
 */
public interface DramaBloomFilterService extends BloomGuard {

    /**
     * 向当前 active filter 添加一个新剧 ID。
     */
    void add(Long id);

    /**
     * 全量重建并原子切换 active 指针。
     */
    BloomRebuildResult rebuild();

    /**
     * active 指针缺失时重建；应用启动时调用。
     */
    void rebuildIfMissing();
}
