package com.msd.content.support;

import com.msd.content.dto.ReindexResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * 最近一次索引重建结果，供状态接口和启动日志使用。
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RagIndexState {

    // 最近一次重建完成时间
    private volatile Instant lastIndexedAt;
    // 最近一次重建的短剧数量
    private volatile int lastDramaCount;
    // 最近一次重建的分集数量
    private volatile int lastEpisodeCount;
    // 最近一次重建是否部分失败
    private volatile boolean lastPartial;
    // 最近一次重建的错误汇总
    private volatile String lastError;
    // 最近一次重建耗时（毫秒）
    private volatile long lastElapsedMs;

    /**
     * 记录一次索引重建结果。
     */
    public void record(ReindexResult result) {
        this.lastIndexedAt = Instant.now();
        this.lastDramaCount = result.dramaCount();
        this.lastEpisodeCount = result.episodeCount();
        this.lastPartial = result.partial();
        this.lastError = result.errors().isEmpty() ? null : String.join("; ", result.errors());
        this.lastElapsedMs = result.elapsedMs();
    }

    /** 最近一次重建完成时间（ISO-8601），从未重建时返回 null。 */
    public String getLastIndexedAt() {
        Instant value = lastIndexedAt;
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value);
    }

    /** 最近一次重建的短剧数量。 */
    public int getLastDramaCount() {
        return lastDramaCount;
    }

    /** 最近一次重建的分集数量。 */
    public int getLastEpisodeCount() {
        return lastEpisodeCount;
    }

    /** 最近一次重建是否部分失败。 */
    public boolean isLastPartial() {
        return lastPartial;
    }

    /** 最近一次重建的错误汇总，无错误时返回 null。 */
    public String getLastError() {
        return lastError;
    }

    /** 最近一次重建耗时（毫秒）。 */
    public long getLastElapsedMs() {
        return lastElapsedMs;
    }
}
