package com.msd.content.service;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.UserBehaviorDTO;
import com.msd.common.result.Result;
import com.msd.content.client.UserBehaviorClient;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.UserPreference;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好画像：把 interaction 的行为权重与 drama 目录元数据合成分类/标签偏好。
 *
 * <p>画像不落库，Redis 缓存 5 分钟；依赖失败时返回空画像并标记 degraded。</p>
 */
@Service
public class UserPreferenceService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(UserPreferenceService.class);
    /** Redis key 前缀。 */
    private static final String CACHE_PREFIX = "msd:rag:pref:";
    /** Redis 连接失败后的退避时间（毫秒）。 */
    private static final long REDIS_BACKOFF_MS = 30_000L;

    // 行为客户端
    private final UserBehaviorClient behaviorClient;
    // 目录缓存
    private final DramaCatalogCacheService catalogCache;
    // Redis 客户端
    private final RedissonClient redissonClient;
    // JSON 序列化
    private final JsonMapper jsonMapper;
    // RAG 配置
    private final RagProperties properties;
    // 冷启动偏好
    private final ColdStartPreferenceService coldStartPreferenceService;
    // Redis 失败后的退避截止时间
    private volatile long redisUnavailableUntil;

    /**
     * 注入行为、目录、缓存、配置和冷启动偏好。
     */
    public UserPreferenceService(UserBehaviorClient behaviorClient,
                                 DramaCatalogCacheService catalogCache,
                                 @Lazy RedissonClient redissonClient,
                                 JsonMapper jsonMapper,
                                 RagProperties properties,
                                 ColdStartPreferenceService coldStartPreferenceService) {
        this.behaviorClient = behaviorClient;
        this.catalogCache = catalogCache;
        this.redissonClient = redissonClient;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.coldStartPreferenceService = coldStartPreferenceService;
    }

    /**
     * 查询用户偏好；无用户或依赖失败时返回空画像。
     */
    public UserPreference get(Long userId) {
        // 1. 未登录用户没有个性化画像
        if (userId == null || userId <= 0) {
            return UserPreference.empty(false);
        }

        // 2. 先读 Redis 缓存
        String key = CACHE_PREFIX + userId;
        UserPreference cached = readCache(key);
        if (cached != null) {
            return cached;
        }

        // 3. 缓存未命中时实时合成；失败不缓存，下一轮可重试
        UserPreference preference;
        try {
            preference = build(userId);
        } catch (Exception ex) {
            log.warn("build user preference failed: userId={}", userId, ex);
            return UserPreference.empty(true);
        }
        if (!preference.degraded()) {
            writeCache(key, preference);
        }
        return preference;
    }

    /**
     * 由行为权重和目录元数据合成分类/标签画像。
     */
    private UserPreference build(Long userId) {
        // 1. 拉取行为；失败直接降级
        Result<UserBehaviorDTO> behaviorResult = behaviorClient.behavior(userId);
        if (behaviorResult == null || !behaviorResult.isSuccess()
                || behaviorResult.getData() == null) {
            log.warn("user behavior unavailable: userId={}", userId);
            return UserPreference.empty(true);
        }
        UserBehaviorDTO behavior = behaviorResult.getData();
        List<UserPreference.DramaWeight> coOccurrence = toCoOccurrence(behavior);
        if (behavior.weights() == null || behavior.weights().isEmpty()) {
            // 新用户没有行为时，优先使用冷启动选择的分类；继续观看/共现仍保留
            List<Long> coldCategories = coldStartPreferenceService.get(userId);
            List<UserPreference.CategoryWeight> categories = coldCategories.stream()
                    .map(id -> new UserPreference.CategoryWeight(id, "", 1.0))
                    .toList();
            List<Long> continueIds = behavior.continueDramaIds() == null
                    ? List.of() : behavior.continueDramaIds();
            List<Long> recent = behavior.recentDramaIds() == null
                    ? List.of() : behavior.recentDramaIds();
            List<Long> favorites = behavior.favoriteDramaIds() == null
                    ? List.of() : behavior.favoriteDramaIds();
            List<Long> disliked = behavior.dislikedDramaIds() == null
                    ? List.of() : behavior.dislikedDramaIds();
            boolean empty = categories.isEmpty() && continueIds.isEmpty()
                    && coOccurrence.isEmpty();
            return new UserPreference(categories, List.of(), recent, favorites,
                    continueIds, coOccurrence, disliked, empty, false);
        }

        // 2. 拉取目录；目录为空时无法映射分类/标签
        List<DramaRagExportDTO> catalog = catalogCache.list();
        if (catalog.isEmpty()) {
            return UserPreference.empty(true);
        }
        Map<Long, DramaRagExportDTO> byId = new LinkedHashMap<>();
        catalog.forEach(drama -> {
            if (drama != null && drama.id() != null) {
                byId.put(drama.id(), drama);
            }
        });

        // 3. 按短剧权重累加分类和标签得分
        Map<Long, Double> categoryScores = new LinkedHashMap<>();
        Map<String, Double> tagScores = new LinkedHashMap<>();
        Map<Long, String> categoryNames = new LinkedHashMap<>();
        for (UserBehaviorDTO.DramaWeight item : behavior.weights()) {
            if (item == null || item.dramaId() == null || item.weight() <= 0) {
                continue;
            }
            DramaRagExportDTO drama = byId.get(item.dramaId());
            if (drama == null) {
                continue;
            }
            if (drama.categoryId() != null) {
                categoryScores.merge(drama.categoryId(), item.weight(), Double::sum);
                if (drama.categoryName() != null && !drama.categoryName().isBlank()) {
                    categoryNames.putIfAbsent(drama.categoryId(), drama.categoryName());
                }
            }
            if (drama.tags() != null) {
                for (String tag : drama.tags()) {
                    if (tag != null && !tag.isBlank()) {
                        tagScores.merge(tag.trim(), item.weight(), Double::sum);
                    }
                }
            }
        }

        // 4. 取 Top 分类和 Top 标签
        List<UserPreference.CategoryWeight> categories = topEntries(categoryScores,
                properties.getPersonalization().getTopCategories()).stream()
                .map(entry -> new UserPreference.CategoryWeight(
                        entry.getKey(),
                        categoryNames.getOrDefault(entry.getKey(), ""),
                        entry.getValue()))
                .toList();
        List<UserPreference.TagWeight> tags = topEntries(tagScores,
                properties.getPersonalization().getTopTags()).stream()
                .map(entry -> new UserPreference.TagWeight(entry.getKey(), entry.getValue()))
                .toList();

        // 5. 空画像表示冷启动，而不是依赖失败；继续观看/共现/负反馈继续透传
        boolean empty = categories.isEmpty() && tags.isEmpty()
                && (behavior.continueDramaIds() == null || behavior.continueDramaIds().isEmpty())
                && (behavior.coOccurrenceDramas() == null || behavior.coOccurrenceDramas().isEmpty());
        return new UserPreference(
                categories,
                tags,
                behavior.recentDramaIds() == null ? List.of() : behavior.recentDramaIds(),
                behavior.favoriteDramaIds() == null ? List.of() : behavior.favoriteDramaIds(),
                behavior.continueDramaIds() == null ? List.of() : behavior.continueDramaIds(),
                List.copyOf(coOccurrence),
                behavior.dislikedDramaIds() == null ? List.of() : behavior.dislikedDramaIds(),
                empty,
                false);
    }

    /**
     * 共同观看召回转内部类型。
     */
    private List<UserPreference.DramaWeight> toCoOccurrence(UserBehaviorDTO behavior) {
        List<UserPreference.DramaWeight> coOccurrence = new ArrayList<>();
        if (behavior.coOccurrenceDramas() != null) {
            for (UserBehaviorDTO.DramaWeight item : behavior.coOccurrenceDramas()) {
                if (item != null && item.dramaId() != null && item.dramaId() > 0) {
                    coOccurrence.add(new UserPreference.DramaWeight(
                            item.dramaId(), item.weight()));
                }
            }
        }
        return List.copyOf(coOccurrence);
    }

    /**
     * 按分数倒序取前 N 个 Map.Entry。
     */
    private <K> List<Map.Entry<K, Double>> topEntries(Map<K, Double> scores, int limit) {
        int safeLimit = Math.max(limit, 1);
        return new ArrayList<>(scores.entrySet()).stream()
                .sorted(Map.Entry.<K, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(safeLimit)
                .toList();
    }

    /**
     * 读取画像缓存，失败时返回 null 走实时计算。
     */
    private UserPreference readCache(String key) {
        // 1. Redis 刚失败过，直接跳过，避免每次请求都等连接超时
        if (System.currentTimeMillis() < redisUnavailableUntil) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            if (json == null || json.isBlank()) {
                return null;
            }
            return jsonMapper.readValue(json, UserPreference.class);
        } catch (Exception ex) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("read preference cache failed: key={}", key, ex);
            return null;
        }
    }

    /**
     * 写入画像缓存；Redis 不可用时静默跳过。
     */
    private void writeCache(String key, UserPreference preference) {
        // 1. Redis 刚失败过，直接跳过
        if (System.currentTimeMillis() < redisUnavailableUntil) {
            return;
        }
        try {
            int minutes = Math.max(properties.getPersonalization().getCacheMinutes(), 1);
            String json = jsonMapper.writeValueAsString(preference);
            redissonClient.getBucket(key).set(json, Duration.ofMinutes(minutes));
        } catch (Exception ex) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("write preference cache failed: key={}", key, ex);
        }
    }
}
