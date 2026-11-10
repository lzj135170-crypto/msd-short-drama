package com.msd.drama.mq;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.msd.drama.mapper.EpisodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * episodeId -> dramaId 的小缓存，避免互动计数失效时反复查库。
 */
@Component
public class EpisodeDramaResolver {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(EpisodeDramaResolver.class);

    // 剧集 Mapper，用于查询剧集所属短剧
    private final EpisodeMapper episodeMapper;
    // episodeId -> dramaId 本地缓存，最多 1 万条，写入 5 分钟后过期
    private final Cache<Long, Optional<Long>> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /**
     * 注入剧集 Mapper。
     */
    public EpisodeDramaResolver(EpisodeMapper episodeMapper) {
        this.episodeMapper = episodeMapper;
    }

    /**
     * 查询剧集所属短剧；不存在或查询失败返回 null。
     */
    public Long resolve(Long episodeId) {
        // 1. 空 ID 直接返回 null
        if (episodeId == null) {
            return null;
        }
        try {
            // 2. 优先读缓存，未命中时查库并把结果（含空值）写入缓存
            return cache.get(episodeId, id -> Optional.ofNullable(episodeMapper.selectDramaIdById(id)))
                    .orElse(null);
        } catch (Exception e) {
            // 3. 查询失败降级为 null，避免阻断互动事件处理
            log.warn("resolve episode drama failed: episodeId={}, error={}", episodeId, e.getMessage());
            return null;
        }
    }
}
