package com.msd.content.service;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 冷启动偏好：新用户选择少量分类后写入 Redis，供画像合成和首屏推荐使用。
 *
 * <p>只保存分类 ID，TTL 30 天；Redis 不可用时静默降级为空偏好。</p>
 */
@Service
public class ColdStartPreferenceService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ColdStartPreferenceService.class);
    /** 冷启动偏好 key 前缀。 */
    private static final String CACHE_PREFIX = "msd:rag:cold-pref:";
    /** 偏好有效期。 */
    private static final Duration TTL = Duration.ofDays(30);
    /** 最多保存的分类数。 */
    private static final int MAX_CATEGORIES = 5;

    // Redis 客户端
    private final RedissonClient redissonClient;

    /**
     * 注入 Redis 客户端。
     */
    public ColdStartPreferenceService(@Lazy RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 读取用户选择的分类 ID；不存在或 Redis 不可用时返回空列表。
     */
    public List<Long> get(Long userId) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(CACHE_PREFIX + userId);
            return parse(bucket.get());
        } catch (Exception e) {
            log.debug("read cold start preference failed: userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 保存用户选择的分类 ID，并清理旧画像/推荐缓存。
     */
    public List<Long> save(Long userId, List<Long> categoryIds) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        // 1. 去重、过滤非法分类，最多保留 5 个
        Set<Long> unique = new LinkedHashSet<>();
        if (categoryIds != null) {
            for (Long categoryId : categoryIds) {
                if (categoryId != null && categoryId > 0) {
                    unique.add(categoryId);
                }
                if (unique.size() >= MAX_CATEGORIES) {
                    break;
                }
            }
        }
        List<Long> saved = new ArrayList<>(unique);
        if (saved.isEmpty()) {
            return List.of();
        }
        // 2. 写入 Redis；失败不阻断请求，但返回已归一化的选择
        try {
            redissonClient.getBucket(CACHE_PREFIX + userId)
                    .set(join(saved), TTL);
            clearUserCache(userId);
        } catch (Exception e) {
            log.warn("save cold start preference failed: userId={}", userId, e);
        }
        return List.copyOf(saved);
    }

    /**
     * 清理画像和个性化结果缓存，保证新选择立即生效。
     */
    private void clearUserCache(Long userId) {
        try {
            redissonClient.getBucket("msd:rag:pref:" + userId).delete();
            redissonClient.getKeys().deleteByPattern("msd:rag:for-you:v3:" + userId + ":*");
        } catch (Exception e) {
            log.debug("clear cold start cache failed: userId={}", userId, e);
        }
    }

    /**
     * 解析逗号分隔的分类 ID。
     */
    private List<Long> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 忽略损坏的单个 ID
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 拼接分类 ID。
     */
    private String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
