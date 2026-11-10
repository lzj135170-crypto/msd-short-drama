package com.msd.content.index;

import com.msd.content.config.RagProperties;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * DRAMA_CHANGED 消费幂等键。
 *
 * <p>用 Redis eventId 去重；Redis 不可用时 fail-open，允许重复处理，
 * 由增量索引的“覆盖写”语义保证最终一致。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class IndexEventDeduplicator {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(IndexEventDeduplicator.class);
    /** 幂等键前缀。 */
    private static final String KEY_PREFIX = "msd:rag:index:evt:";

    // Redisson 客户端
    private final RedissonClient redissonClient;
    // 幂等键保留小时数
    private final int ttlHours;

    /**
     * 注入懒加载的 Redisson 客户端和 RAG 配置。
     */
    public IndexEventDeduplicator(@Lazy RedissonClient redissonClient,
                                  RagProperties properties) {
        this.redissonClient = redissonClient;
        this.ttlHours = Math.max(properties.getIncremental().getDedupTtlHours(), 1);
    }

    /**
     * 判断事件是否已处理；Redis 不可用时按未处理放行。
     */
    public boolean isProcessed(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        try {
            return redissonClient.getBucket(KEY_PREFIX + eventId).isExists();
        } catch (Exception ex) {
            log.debug("index dedup check unavailable: eventId={}, error={}",
                    eventId, ex.getMessage());
            return false;
        }
    }

    /**
     * 标记事件已处理。
     *
     * @return true 表示首次处理；false 表示重复事件
     */
    public boolean markIfNew(String eventId) {
        // 1. 非法事件 ID 不能作为幂等键
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + eventId);
            return bucket.setIfAbsent("1", Duration.ofHours(ttlHours));
        } catch (Exception ex) {
            // 2. Redis 故障时放行，避免索引完全停摆
            log.debug("index dedup unavailable: eventId={}, error={}",
                    eventId, ex.getMessage());
            return true;
        }
    }
}
