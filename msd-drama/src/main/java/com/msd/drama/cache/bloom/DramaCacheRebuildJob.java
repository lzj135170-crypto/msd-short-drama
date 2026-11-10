package com.msd.drama.cache.bloom;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * xxl-job 定时重建短剧布隆过滤器。
 */
@Component
@ConditionalOnProperty(prefix = "msd.cache.bloom", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DramaCacheRebuildJob {

    // 布隆过滤器服务
    private final DramaBloomFilterService bloomFilterService;

    /**
     * 注入布隆过滤器服务。
     */
    public DramaCacheRebuildJob(DramaBloomFilterService bloomFilterService) {
        this.bloomFilterService = bloomFilterService;
    }

    /**
     * JobHandler：重建 filter 并原子切换 active 指针。
     */
    @XxlJob("dramaCacheRebuildHandler")
    public void rebuild() {
        try {
            // 1. 全量重建并原子切换 active 指针
            BloomRebuildResult result = bloomFilterService.rebuild();
            // 2. 输出重建统计并标记任务成功
            XxlJobHelper.log("bloom rebuild: total={}, added={}, filter={}, old={}, elapsedMs={}",
                    result.total(), result.added(), result.filterKey(),
                    result.oldFilterKey(), result.elapsedMs());
            XxlJobHelper.handleSuccess("bloom rebuild success");
        } catch (Exception e) {
            // 3. 记录异常并标记任务失败
            XxlJobHelper.log(e);
            XxlJobHelper.handleFail("bloom rebuild failed: " + e.getMessage());
        }
    }
}
