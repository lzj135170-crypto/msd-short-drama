package com.msd.content.service;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.content.client.RecommendationExposureClient;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.PersonalizedDramaCard;
import com.msd.content.dto.PersonalizedFeed;
import com.msd.content.dto.RecommendationExposureRequest;
import com.msd.content.dto.UserPreference;
import com.msd.content.rag.RagMetadata;
import com.msd.content.support.LazyMilvusStoreProvider;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 首页「为你推荐」：基于用户偏好对已上架短剧做规则排序，不调用 LLM。
 *
 * <p>无偏好或依赖失败时回退热播，保证接口始终可返回。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PersonalizedRecommendService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(PersonalizedRecommendService.class);
    /** 缓存 key 前缀；v3 避免命中旧版无归因字段的缓存。 */
    private static final String CACHE_PREFIX = "msd:rag:for-you:v3:";
    /** 算法版本。 */
    private static final String ALGORITHM = "v2";
    /** 实验 ID。 */
    private static final String EXPERIMENT_ID = "for-you-v2";
    /** 继续观看最多取几部。 */
    private static final int CONTINUE_ROUTE_LIMIT = 3;
    /** 同分类最多入选几部，第二轮回填时放开。 */
    private static final int MAX_PER_CATEGORY = 2;
    /** 向量召回条数。 */
    private static final int VECTOR_ROUTE_TOP_K = 20;
    /** 默认返回条数。 */
    private static final int DEFAULT_LIMIT = 6;
    /** 最大返回条数。 */
    private static final int MAX_LIMIT = 12;
    /** 新上架判定天数。 */
    private static final int FRESH_DAYS = 30;
    /** Redis 连接失败后的退避时间（毫秒）。 */
    private static final long REDIS_BACKOFF_MS = 30_000L;
    /** 冷启动推荐理由。 */
    private static final String REASON_HOT = "热门推荐";
    /** 热门兜底推荐理由。 */
    private static final String REASON_POPULAR = "本周热播";
    /** 依赖降级时的推荐理由。 */
    private static final String REASON_COLD = "暂时按热播推荐";
    /** 仅因上新入选时的推荐理由。 */
    private static final String REASON_FRESH = "最近上新，适合尝鲜";

    // 用户偏好服务
    private final UserPreferenceService preferenceService;
    // 短剧目录缓存
    private final DramaCatalogCacheService catalogCache;
    // Redis 客户端
    private final RedissonClient redissonClient;
    // JSON 序列化
    private final JsonMapper jsonMapper;
    // RAG 配置
    private final RagProperties properties;
    // 向量召回提供者，测试可为 null
    private final LazyMilvusStoreProvider milvusProvider;
    // 曝光上报客户端，测试可为 null
    private final RecommendationExposureClient exposureClient;
    // Redis 失败后的退避截止时间
    private volatile long redisUnavailableUntil;

    /**
     * 兼容旧调用方，不启用向量召回和曝光上报。
     */
    public PersonalizedRecommendService(UserPreferenceService preferenceService,
                                        DramaCatalogCacheService catalogCache,
                                        @Lazy RedissonClient redissonClient,
                                        JsonMapper jsonMapper,
                                        RagProperties properties) {
        this(preferenceService, catalogCache, redissonClient, jsonMapper, properties,
                null, null);
    }

    /**
     * 注入偏好、目录、缓存、向量召回和曝光上报。
     */
    @Autowired
    public PersonalizedRecommendService(UserPreferenceService preferenceService,
                                        DramaCatalogCacheService catalogCache,
                                        @Lazy RedissonClient redissonClient,
                                        JsonMapper jsonMapper,
                                        RagProperties properties,
                                        LazyMilvusStoreProvider milvusProvider,
                                        RecommendationExposureClient exposureClient) {
        this.preferenceService = preferenceService;
        this.catalogCache = catalogCache;
        this.redissonClient = redissonClient;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.milvusProvider = milvusProvider;
        this.exposureClient = exposureClient;
    }

    /**
     * 生成首页推荐；多路召回后排序，并上报曝光。
     */
    public PersonalizedFeed feed(Long userId, int requestedLimit) {
        int limit = clampLimit(requestedLimit);
        // 1. 有用户时先读结果缓存；命中后只更换 requestId，保证每次曝光可归因
        String cacheKey = userId == null ? null : CACHE_PREFIX + userId + ":" + limit;
        PersonalizedFeed cached = readCache(cacheKey);
        if (cached != null) {
            PersonalizedFeed fresh = withRequest(cached, userId);
            reportExposure(userId, fresh);
            return fresh;
        }

        // 2. 读取目录和偏好；目录为空时直接返回空结果
        List<DramaRagExportDTO> catalog = catalogCache.list();
        if (catalog.isEmpty()) {
            return PersonalizedFeed.empty(true);
        }
        UserPreference preference = preferenceService.get(userId);
        boolean hasRoute = hasRouteCandidates(preference);
        PersonalizedFeed result;
        if (preference.empty() && !hasRoute) {
            // 3. 冷启动/依赖失败回退热播，并说明推荐依据
            String reason = preference.degraded() ? REASON_COLD : REASON_HOT;
            result = withMeta(hotCards(catalog, limit, reason),
                    false, true, preference.degraded(), userId);
        } else {
            // 4. 继续观看、共同观看、向量、偏好和热播多路召回
            RankedResult ranked = rankV2(catalog, preference, limit);
            result = withMeta(ranked.cards(), true, false,
                    preference.degraded() || ranked.degraded(), userId);
        }

        // 5. 写入结果缓存；降级结果不缓存，方便快速恢复
        if (!result.degraded()) {
            writeCache(cacheKey, result);
        }
        reportExposure(userId, result);
        return result;
    }

    /**
     * 多路召回排序：
     * 继续观看 > 共同观看 > 向量/偏好 > 热播，并做同分类多样性约束。
     */
    private RankedResult rankV2(List<DramaRagExportDTO> catalog,
                                UserPreference preference, int limit) {
        Map<Long, DramaRagExportDTO> byId = new LinkedHashMap<>();
        for (DramaRagExportDTO drama : catalog) {
            if (drama != null && drama.id() != null) {
                byId.put(drama.id(), drama);
            }
        }
        Set<Long> disliked = new HashSet<>(preference.dislikedDramaIds());
        Set<Long> excluded = new HashSet<>();
        excluded.addAll(preference.recentDramaIds());
        excluded.addAll(preference.favoriteDramaIds());
        excluded.addAll(disliked);

        Map<Long, RouteCandidate> best = new LinkedHashMap<>();

        // 1. 继续观看：允许最近已看，但不能是用户明确不感兴趣的
        int continueCount = 0;
        for (Long dramaId : preference.continueDramaIds()) {
            if (continueCount >= CONTINUE_ROUTE_LIMIT) {
                break;
            }
            DramaRagExportDTO drama = byId.get(dramaId);
            if (drama == null || disliked.contains(dramaId)) {
                continue;
            }
            offer(best, drama, 6.0, "继续看《" + safeTitle(drama) + "》", List.of());
            continueCount += 1;
        }

        // 2. 共同观看：看过同一部剧的人还看了什么
        for (UserPreference.DramaWeight item : preference.coOccurrenceDramas()) {
            if (item == null || item.dramaId() == null || item.dramaId() <= 0) {
                continue;
            }
            DramaRagExportDTO drama = byId.get(item.dramaId());
            if (drama == null || excluded.contains(drama.id())) {
                continue;
            }
            double score = 4.0 + Math.min(Math.max(item.score(), 0), 5.0) * 0.2;
            offer(best, drama, score, "看过的人也在看", List.of());
        }

        // 3. 向量召回：用偏好检索词召回相似短剧
        boolean vectorDegraded = false;
        String query = preference.preferenceQuery();
        if (milvusProvider != null && query != null && !query.isBlank()) {
            try {
                List<Document> documents = milvusProvider.search(query, VECTOR_ROUTE_TOP_K);
                for (Document document : documents) {
                    if (document == null) {
                        continue;
                    }
                    Map<String, Object> metadata = document.getMetadata();
                    if (!RagMetadata.DOC_TYPE_DRAMA.equals(
                            stringValue(metadata.get(RagMetadata.DOC_TYPE)))) {
                        continue;
                    }
                    Long dramaId = longValue(metadata.get(RagMetadata.DRAMA_ID));
                    DramaRagExportDTO drama = dramaId == null ? null : byId.get(dramaId);
                    if (drama == null || excluded.contains(drama.id())) {
                        continue;
                    }
                    double score = 3.0 + Math.min(Math.max(
                            document.getScore() == null ? 0 : document.getScore(), 0), 1.0);
                    offer(best, drama, score, "和你看过的题材相似", List.of());
                }
            } catch (Exception e) {
                vectorDegraded = true;
                log.debug("personalized vector route failed: userId={}", preference.hashCode(), e);
            }
        }

        // 4. 偏好规则：分类、标签、热度和上新
        Map<Long, Double> categoryScores = normalizeCategories(preference.categories());
        Map<String, Double> tagScores = normalizeTags(preference.tags());
        for (DramaRagExportDTO drama : catalog) {
            if (drama == null || drama.id() == null || excluded.contains(drama.id())) {
                continue;
            }
            double categoryScore = categoryScores.getOrDefault(drama.categoryId(), 0.0);
            List<String> matchedTags = matchedTags(drama.tags(), tagScores);
            double score = 3.0 * categoryScore;
            score += 2.0 * tagScore(drama.tags(), tagScores);
            score += 0.5 * Math.log1p(popularity(drama));
            if (isFresh(drama.publishTime())) {
                score += 1.0;
            }
            if (score > 0) {
                offer(best, drama, score, reasonFor(drama, categoryScore > 0, matchedTags),
                        matchedTags);
            }
        }

        // 5. 热播兜底：保证候选足够，同时带有可读理由
        for (DramaRagExportDTO drama : catalog) {
            if (drama == null || drama.id() == null || excluded.contains(drama.id())) {
                continue;
            }
            double score = 0.5 * Math.log1p(popularity(drama));
            String reason = isFresh(drama.publishTime()) ? REASON_FRESH : REASON_POPULAR;
            offer(best, drama, score, reason, List.of());
        }

        // 6. 排序并做多样性约束
        List<RouteCandidate> candidates = new ArrayList<>(best.values());
        candidates.sort(Comparator.comparingDouble(RouteCandidate::score).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (RouteCandidate item) -> popularity(item.drama())).reversed())
                .thenComparing(item -> item.drama().id(),
                        Comparator.nullsLast(Long::compareTo)));
        return new RankedResult(selectDiverse(candidates, limit), vectorDegraded);
    }

    /**
     * 同一分类最多先选 2 部；不足时再放开限制回填。
     */
    private List<PersonalizedDramaCard> selectDiverse(List<RouteCandidate> candidates, int limit) {
        List<RouteCandidate> selected = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        Map<Long, Integer> categoryCounts = new LinkedHashMap<>();
        for (RouteCandidate candidate : candidates) {
            if (selected.size() >= limit) {
                break;
            }
            Long categoryId = candidate.drama().categoryId();
            long key = categoryId == null ? -1L : categoryId;
            int count = categoryCounts.getOrDefault(key, 0);
            if (count >= MAX_PER_CATEGORY) {
                continue;
            }
            if (selectedIds.add(candidate.drama().id())) {
                selected.add(candidate);
                categoryCounts.put(key, count + 1);
            }
        }
        if (selected.size() < limit) {
            for (RouteCandidate candidate : candidates) {
                if (selected.size() >= limit) {
                    break;
                }
                if (selectedIds.add(candidate.drama().id())) {
                    selected.add(candidate);
                }
            }
        }
        return selected.stream()
                .map(item -> toCard(item.drama(), item.reason(), item.matchedTags()))
                .toList();
    }

    /**
     * 保留得分更高的路线及推荐理由。
     */
    private void offer(Map<Long, RouteCandidate> best, DramaRagExportDTO drama,
                       double score, String reason, List<String> matchedTags) {
        if (drama == null || drama.id() == null) {
            return;
        }
        RouteCandidate existing = best.get(drama.id());
        if (existing == null || score > existing.score()) {
            best.put(drama.id(), new RouteCandidate(drama, score, reason,
                    matchedTags == null ? List.of() : List.copyOf(matchedTags)));
        }
    }

    /**
     * 是否有继续观看或共同观看候选。
     */
    private boolean hasRouteCandidates(UserPreference preference) {
        return (preference.continueDramaIds() != null && !preference.continueDramaIds().isEmpty())
                || (preference.coOccurrenceDramas() != null
                && !preference.coOccurrenceDramas().isEmpty());
    }

    /**
     * 给结果补上归因元信息。
     */
    private PersonalizedFeed withMeta(List<PersonalizedDramaCard> items,
                                      boolean personalized, boolean coldStart,
                                      boolean degraded, Long userId) {
        return new PersonalizedFeed(items, personalized, coldStart, degraded,
                UUID.randomUUID().toString(), ALGORITHM, EXPERIMENT_ID, variant(userId));
    }

    /**
     * 缓存命中时只更换 requestId，保留算法和实验分桶。
     */
    private PersonalizedFeed withRequest(PersonalizedFeed feed, Long userId) {
        return new PersonalizedFeed(feed.items(), feed.personalized(), feed.coldStart(),
                feed.degraded(), UUID.randomUUID().toString(), ALGORITHM,
                EXPERIMENT_ID, variant(userId));
    }

    /**
     * 简单的稳定分桶。
     */
    private String variant(Long userId) {
        return userId == null ? "anon" : (userId % 2 == 0 ? "B" : "A");
    }

    /**
     * 上报曝光；失败不影响推荐返回。
     */
    private void reportExposure(Long userId, PersonalizedFeed feed) {
        if (userId == null || exposureClient == null
                || feed == null || feed.items().isEmpty()) {
            return;
        }
        try {
            List<RecommendationExposureRequest.Item> items = new ArrayList<>();
            int position = 1;
            for (PersonalizedDramaCard card : feed.items()) {
                if (card != null && card.dramaId() != null) {
                    items.add(new RecommendationExposureRequest.Item(
                            card.dramaId(), position++, card.reason()));
                }
            }
            RecommendationExposureRequest request = new RecommendationExposureRequest(
                    userId, feed.requestId(), "HOME_FEED", feed.algorithm(),
                    feed.experimentId(), feed.variant(), items);
            // 曝光是旁路指标，异步上报，不拖慢首页响应
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    exposureClient.record(request);
                } catch (Exception e) {
                    log.debug("report personalized exposure failed: userId={}", userId, e);
                }
            });
        } catch (Exception e) {
            log.debug("build personalized exposure failed: userId={}", userId, e);
        }
    }

    /**
     * 清理某用户的个性化缓存，供冷启动偏好保存后立即生效。
     */
    public void evictUser(Long userId) {
        if (userId == null || userId <= 0 || System.currentTimeMillis() < redisUnavailableUntil) {
            return;
        }
        try {
            redissonClient.getBucket("msd:rag:pref:" + userId).delete();
            redissonClient.getKeys().deleteByPattern(CACHE_PREFIX + userId + ":*");
        } catch (Exception e) {
            log.debug("evict personalized cache failed: userId={}", userId, e);
        }
    }

    /**
     * 安全标题，避免空标题拼出难读理由。
     */
    private String safeTitle(DramaRagExportDTO drama) {
        return drama == null || drama.title() == null || drama.title().isBlank()
                ? "这部剧" : drama.title();
    }

    /**
     * 多路排序结果。
     */
    private record RankedResult(List<PersonalizedDramaCard> cards, boolean degraded) {
    }

    /**
     * 带路线得分的候选。
     */
    private record RouteCandidate(DramaRagExportDTO drama, double score,
                                  String reason, List<String> matchedTags) {
    }

    /**
     * 直接取热播前 N 部，并附带推荐理由。
     */
    private List<PersonalizedDramaCard> hotCards(List<DramaRagExportDTO> catalog, int limit,
                                                  String reason) {
        return hotDramas(catalog).stream()
                .limit(limit)
                .map(drama -> toCard(drama, reason, List.of()))
                .toList();
    }

    /**
     * 按现有首页热度公式排序。
     */
    private List<DramaRagExportDTO> hotDramas(List<DramaRagExportDTO> catalog) {
        return catalog.stream()
                .filter(drama -> drama != null && drama.id() != null)
                .sorted(Comparator.comparingDouble(this::popularity).reversed()
                        .thenComparing(drama -> drama.id(), Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    /**
     * 分类权重归一化到 0-1。
     */
    private Map<Long, Double> normalizeCategories(List<UserPreference.CategoryWeight> categories) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        double max = 0.0;
        for (UserPreference.CategoryWeight category : categories) {
            if (category != null && category.id() != null) {
                scores.put(category.id(), category.score());
                max = Math.max(max, category.score());
            }
        }
        if (max <= 0) {
            return Map.of();
        }
        double divisor = max;
        scores.replaceAll((id, score) -> score / divisor);
        return scores;
    }

    /**
     * 标签权重归一化到 0-1。
     */
    private Map<String, Double> normalizeTags(List<UserPreference.TagWeight> tags) {
        Map<String, Double> scores = new LinkedHashMap<>();
        double max = 0.0;
        for (UserPreference.TagWeight tag : tags) {
            if (tag != null && tag.name() != null && !tag.name().isBlank()) {
                scores.put(tag.name(), tag.score());
                max = Math.max(max, tag.score());
            }
        }
        if (max <= 0) {
            return Map.of();
        }
        double divisor = max;
        scores.replaceAll((name, score) -> score / divisor);
        return scores;
    }

    /**
     * 计算一部剧命中的标签权重。
     */
    private double tagScore(List<String> tags, Map<String, Double> tagScores) {
        if (tags == null || tags.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        for (String tag : tags) {
            if (tag != null) {
                score += tagScores.getOrDefault(tag, 0.0);
            }
        }
        return score;
    }

    /**
     * 热度公式与首页热播保持一致。
     */
    private double popularity(DramaRagExportDTO drama) {
        long view = drama.viewCount() == null ? 0 : drama.viewCount();
        long like = drama.likeCount() == null ? 0 : drama.likeCount();
        long favorite = drama.favoriteCount() == null ? 0 : drama.favoriteCount();
        long comment = drama.commentCount() == null ? 0 : drama.commentCount();
        return view + like * 3.0 + favorite * 5.0 + comment * 2.0;
    }

    /**
     * 判断是否 30 天内上新。
     */
    private boolean isFresh(String publishTime) {
        if (publishTime == null || publishTime.isBlank()) {
            return false;
        }
        try {
            return LocalDateTime.parse(publishTime).isAfter(LocalDateTime.now().minusDays(FRESH_DAYS));
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 单张目录 DTO 转推荐卡片。
     */
    private PersonalizedDramaCard toCard(DramaRagExportDTO drama, String reason,
                                         List<String> matchedTags) {
        return new PersonalizedDramaCard(
                drama.id(),
                drama.title(),
                drama.subtitle(),
                drama.coverUrl(),
                drama.categoryName(),
                drama.episodeCount(),
                drama.viewCount(),
                drama.likeCount(),
                reason,
                matchedTags);
    }

    /**
     * 找出短剧标签中命中用户偏好的部分，最多返回 3 个。
     */
    private List<String> matchedTags(List<String> tags, Map<String, Double> tagScores) {
        if (tags == null || tags.isEmpty() || tagScores.isEmpty()) {
            return List.of();
        }
        Set<String> matches = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag != null && tagScores.containsKey(tag)) {
                matches.add(tag);
                if (matches.size() >= 3) {
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 生成推荐理由：优先说明命中的分类/标签，其次说明上新或热播。
     */
    private String reasonFor(DramaRagExportDTO drama, boolean categoryMatched,
                             List<String> matchedTags) {
        // 1. 同时命中分类和标签时给出完整解释
        String categoryName = drama.categoryName() == null || drama.categoryName().isBlank()
                ? "这类短剧" : drama.categoryName();
        if (categoryMatched && !matchedTags.isEmpty()) {
            return "因为你喜欢" + categoryName + "，也常看「" + matchedTags.get(0) + "」";
        }
        // 2. 只命中分类
        if (categoryMatched) {
            return "因为你喜欢" + categoryName;
        }
        // 3. 只命中标签
        if (!matchedTags.isEmpty()) {
            return "因为你常看「" + matchedTags.get(0) + "」";
        }
        // 4. 没有偏好命中时说明热播或上新依据
        return isFresh(drama.publishTime()) ? REASON_FRESH : REASON_POPULAR;
    }

    /**
     * 任意对象转字符串。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 任意对象转 Long，无法转换时返回 null。
     */
    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 限制返回条数。
     */
    private int clampLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    /**
     * 读取结果缓存。
     */
    private PersonalizedFeed readCache(String key) {
        if (key == null || System.currentTimeMillis() < redisUnavailableUntil) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            if (json == null || json.isBlank()) {
                return null;
            }
            return jsonMapper.readValue(json, PersonalizedFeed.class);
        } catch (Exception ex) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("read personalized cache failed: key={}", key, ex);
            return null;
        }
    }

    /**
     * 写入结果缓存。
     */
    private void writeCache(String key, PersonalizedFeed feed) {
        if (key == null || System.currentTimeMillis() < redisUnavailableUntil) {
            return;
        }
        try {
            int minutes = Math.max(properties.getPersonalization().getCacheMinutes(), 1);
            String json = jsonMapper.writeValueAsString(feed);
            redissonClient.getBucket(key).set(json, Duration.ofMinutes(minutes));
        } catch (Exception ex) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("write personalized cache failed: key={}", key, ex);
        }
    }

}
