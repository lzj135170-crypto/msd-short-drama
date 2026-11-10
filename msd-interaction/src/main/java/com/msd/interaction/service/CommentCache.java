package com.msd.interaction.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.msd.interaction.entity.Comment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 评论首页短期缓存：只缓存原始评论行，当前用户 liked 状态在返回前合并。
 *
 * <p>评论创建、删除、隐藏后按 dramaId 失效，避免用户看到旧 replyCount。</p>
 */
@Component
public class CommentCache {

    // 是否启用
    private final boolean enabled;
    // 本地缓存
    private final Cache<Key, PageData> cache;

    /**
     * 注入开关和 TTL。
     */
    public CommentCache(
            @Value("${msd.interaction.comment-cache.enabled:true}") boolean enabled,
            @Value("${msd.interaction.comment-cache.ttl-seconds:15}") long ttlSeconds) {
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofSeconds(Math.max(ttlSeconds, 1)))
                .build();
    }

    /**
     * 读取一页根评论。
     */
    public PageData get(Long dramaId, Long episodeId, int pageNo, int pageSize) {
        return enabled ? cache.getIfPresent(new Key(dramaId, episodeId, pageNo, pageSize)) : null;
    }

    /**
     * 写入一页根评论。
     */
    public void put(Long dramaId, Long episodeId, int pageNo, int pageSize, PageData page) {
        if (enabled && page != null) {
            cache.put(new Key(dramaId, episodeId, pageNo, pageSize), page);
        }
    }

    /**
     * 失效某部短剧的全部评论页。
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
    private record Key(Long dramaId, Long episodeId, int pageNo, int pageSize) {
    }

    /**
     * 缓存的原始评论页。
     */
    public record PageData(long total, List<Comment> comments) {
    }
}
