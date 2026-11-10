package com.msd.interaction.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.msd.interaction.dto.CommentSummaryView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * 评论课代表本地缓存，按短剧/剧集维度缓存 10 分钟。
 */
@Component
public class CommentSummaryCache {

    // 是否启用
    private final boolean enabled;
    // 本地缓存
    private final Cache<Key, CommentSummaryView> cache;

    /**
     * 注入开关和 TTL。
     */
    public CommentSummaryCache(
            @Value("${msd.interaction.comment-summary.cache-enabled:true}") boolean enabled,
            @Value("${msd.interaction.comment-summary.cache-ttl-seconds:600}") long ttlSeconds) {
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder()
                .maximumSize(2_000)
                .expireAfterWrite(Duration.ofSeconds(Math.max(ttlSeconds, 1)))
                .build();
    }

    /**
     * 读取缓存。
     */
    public CommentSummaryView get(Long dramaId, Long episodeId) {
        return enabled ? cache.getIfPresent(new Key(dramaId, episodeId)) : null;
    }

    /**
     * 写入缓存。
     */
    public void put(Long dramaId, Long episodeId, CommentSummaryView view) {
        if (enabled && view != null) {
            cache.put(new Key(dramaId, episodeId), view);
        }
    }

    /**
     * 清理某部短剧的所有剧集缓存。
     */
    public void invalidateDrama(Long dramaId) {
        if (!enabled || dramaId == null) {
            return;
        }
        cache.asMap().keySet().removeIf(key -> Objects.equals(key.dramaId(), dramaId));
    }

    /**
     * 缓存 key。
     */
    private record Key(Long dramaId, Long episodeId) {
    }
}
