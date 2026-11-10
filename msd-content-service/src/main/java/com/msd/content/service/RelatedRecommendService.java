package com.msd.content.service;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.RelatedDramaCard;
import com.msd.content.dto.RelatedFeed;
import com.msd.content.rag.RagMetadata;
import com.msd.content.support.LazyMilvusStoreProvider;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相似短剧推荐。
 *
 * <p>优先用当前短剧文本在 Milvus 中做向量召回，排除自身；向量不可用或没有结果时
 * 回退同分类、共同标签和热度规则。不调用 LLM。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RelatedRecommendService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(RelatedRecommendService.class);
    /** 缓存 key 前缀。 */
    private static final String CACHE_PREFIX = "msd:rag:related:v1:";
    /** 默认返回条数。 */
    private static final int DEFAULT_LIMIT = 6;
    /** 最大返回条数。 */
    private static final int MAX_LIMIT = 12;
    /** 向量召回时额外多取几条，用于排除自身和去重。 */
    private static final int VECTOR_EXTRA = 4;
    /** 查询文本中的简介最大字符数。 */
    private static final int QUERY_DESCRIPTION_CHARS = 400;
    /** Redis 连接失败后的退避时间（毫秒）。 */
    private static final long REDIS_BACKOFF_MS = 30_000L;

    // 短剧目录客户端
    private final DramaCatalogClient catalogClient;
    // Milvus 延迟提供者
    private final LazyMilvusStoreProvider milvusProvider;
    // RAG 配置
    private final RagProperties properties;
    // Redis 客户端，测试中可为 null
    private final RedissonClient redissonClient;
    // JSON 序列化，测试中可为 null
    private final JsonMapper jsonMapper;
    // Redis 失败后的退避截止时间
    private volatile long redisUnavailableUntil;

    /**
     * 注入目录、向量库、配置和缓存依赖。
     */
    public RelatedRecommendService(DramaCatalogClient catalogClient,
                                   LazyMilvusStoreProvider milvusProvider,
                                   RagProperties properties,
                                   @Lazy RedissonClient redissonClient,
                                   JsonMapper jsonMapper) {
        this.catalogClient = catalogClient;
        this.milvusProvider = milvusProvider;
        this.properties = properties;
        this.redissonClient = redissonClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 查询相似短剧。
     *
     * @param dramaId 当前短剧 ID
     * @param limit   返回条数
     */
    public RelatedFeed feed(Long dramaId, int limit) {
        // 1. 参数非法时返回空结果
        if (dramaId == null || dramaId <= 0) {
            return RelatedFeed.empty(false);
        }
        int safeLimit = clampLimit(limit);
        String cacheKey = CACHE_PREFIX + dramaId + ":" + safeLimit;

        // 2. 优先读缓存，避免详情页反复触发向量检索
        RelatedFeed cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 3. 读取源短剧；源短剧不可见时无需推荐
        DramaRagExportDTO source;
        try {
            Result<DramaRagExportDTO> response = catalogClient.detail(dramaId);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                return RelatedFeed.empty(false);
            }
            source = response.getData();
        } catch (Exception e) {
            log.warn("related source lookup failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
            return RelatedFeed.empty(true);
        }

        // 4. 向量召回：用当前短剧文本作为查询，排除自身
        boolean vectorFailed = false;
        List<RelatedDramaCard> vectorItems = List.of();
        try {
            List<Document> documents = milvusProvider.search(
                    queryText(source), safeLimit + VECTOR_EXTRA);
            vectorItems = toCards(documents, source, safeLimit);
        } catch (Exception e) {
            vectorFailed = true;
            log.warn("related vector search failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
        }
        if (!vectorItems.isEmpty()) {
            RelatedFeed result = new RelatedFeed(vectorItems, "vector", false);
            writeCache(cacheKey, result);
            return result;
        }

        // 5. 向量为空或不可用时回退规则推荐
        List<RelatedDramaCard> ruleItems = ruleCards(dramaId, source, safeLimit);
        RelatedFeed result = ruleItems.isEmpty()
                ? RelatedFeed.empty(vectorFailed)
                : new RelatedFeed(ruleItems, "rule", vectorFailed);
        writeCache(cacheKey, result);
        return result;
    }

    /**
     * 规则兜底：同分类、共同标签优先，再按热度排序。
     */
    private List<RelatedDramaCard> ruleCards(Long dramaId, DramaRagExportDTO source, int limit) {
        try {
            Result<List<DramaRagExportDTO>> response = catalogClient.relatedFallback(dramaId, limit);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                return List.of();
            }
            Set<Long> seen = new HashSet<>();
            List<RelatedDramaCard> cards = new ArrayList<>();
            for (DramaRagExportDTO drama : response.getData()) {
                if (drama == null || drama.id() == null || drama.id().equals(dramaId)
                        || !seen.add(drama.id())) {
                    continue;
                }
                cards.add(toCard(drama, source, 0.0));
                if (cards.size() >= limit) {
                    break;
                }
            }
            return List.copyOf(cards);
        } catch (Exception e) {
            log.warn("related rule fallback failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
            return List.of();
        }
    }

    /**
     * 向量文档转卡片，过滤自身、非短剧文档和重复项。
     */
    private List<RelatedDramaCard> toCards(List<Document> documents, DramaRagExportDTO source,
                                           int limit) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new HashSet<>();
        List<RelatedDramaCard> cards = new ArrayList<>();
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
            if (dramaId == null || dramaId.equals(source.id()) || !seen.add(dramaId)) {
                continue;
            }
            List<String> tags = stringList(metadata.get(RagMetadata.TAGS));
            String categoryName = stringValue(metadata.get(RagMetadata.CATEGORY_NAME));
            cards.add(new RelatedDramaCard(
                    dramaId,
                    stringValue(metadata.get(RagMetadata.TITLE)),
                    stringValue(metadata.get(RagMetadata.SUBTITLE)),
                    stringValue(metadata.get(RagMetadata.COVER_URL)),
                    categoryName,
                    integerValue(metadata.get(RagMetadata.EPISODE_COUNT)),
                    longValue(metadata.get(RagMetadata.VIEW_COUNT)),
                    longValue(metadata.get(RagMetadata.LIKE_COUNT)),
                    document.getScore(),
                    reasonFor(source, tags, categoryName),
                    matchedTags(source.tags(), tags)));
            if (cards.size() >= limit) {
                break;
            }
        }
        return List.copyOf(cards);
    }

    /**
     * 目录 DTO 转卡片。
     */
    private RelatedDramaCard toCard(DramaRagExportDTO drama, DramaRagExportDTO source,
                                    double score) {
        return new RelatedDramaCard(
                drama.id(),
                drama.title(),
                drama.subtitle(),
                drama.coverUrl(),
                drama.categoryName(),
                drama.episodeCount(),
                drama.viewCount(),
                drama.likeCount(),
                score,
                reasonFor(source, drama.tags(), drama.categoryName()),
                matchedTags(source.tags(), drama.tags()));
    }

    /**
     * 生成可解释的推荐理由。
     */
    private String reasonFor(DramaRagExportDTO source, List<String> tags, String categoryName) {
        List<String> matches = matchedTags(source.tags(), tags);
        if (!matches.isEmpty()) {
            return "同为「" + matches.get(0) + "」题材";
        }
        if (source.categoryId() != null && source.categoryName() != null
                && source.categoryName().equals(categoryName)) {
            return "同分类 · 相似剧情";
        }
        return "你可能也喜欢";
    }

    /**
     * 计算当前短剧与候选短剧的共同标签，最多返回 3 个。
     */
    private List<String> matchedTags(List<String> sourceTags, List<String> candidateTags) {
        if (sourceTags == null || sourceTags.isEmpty()
                || candidateTags == null || candidateTags.isEmpty()) {
            return List.of();
        }
        Set<String> candidateSet = new HashSet<>(candidateTags);
        Set<String> matches = new LinkedHashSet<>();
        for (String tag : sourceTags) {
            if (tag != null && candidateSet.contains(tag)) {
                matches.add(tag);
                if (matches.size() >= 3) {
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 用源短剧的标题、分类、标签和简介构造向量查询文本。
     */
    private String queryText(DramaRagExportDTO source) {
        StringBuilder text = new StringBuilder();
        appendLine(text, "标题", source.title());
        appendLine(text, "副标题", source.subtitle());
        appendLine(text, "分类", source.categoryName());
        if (source.tags() != null && !source.tags().isEmpty()) {
            appendLine(text, "标签", String.join("、", source.tags()));
        }
        String description = source.description() == null ? "" : source.description();
        int maxChars = Math.min(QUERY_DESCRIPTION_CHARS,
                Math.max(properties.getDocument().getMaxDescriptionChars(), 1));
        if (description.length() > maxChars) {
            description = description.substring(0, maxChars);
        }
        appendLine(text, "简介", description);
        return text.toString().trim();
    }

    /**
     * 追加一行“字段：值”，空值跳过。
     */
    private void appendLine(StringBuilder text, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        text.append(label).append('：').append(value.trim()).append('\n');
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
    private RelatedFeed readCache(String key) {
        if (redissonClient == null || jsonMapper == null
                || System.currentTimeMillis() < redisUnavailableUntil) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();
            if (json == null || json.isBlank()) {
                return null;
            }
            return jsonMapper.readValue(json, RelatedFeed.class);
        } catch (Exception e) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("read related cache failed: key={}", key, e);
            return null;
        }
    }

    /**
     * 写入结果缓存，空且降级的结果不缓存，方便快速恢复。
     */
    private void writeCache(String key, RelatedFeed feed) {
        if (feed == null || feed.items().isEmpty() || redissonClient == null || jsonMapper == null
                || System.currentTimeMillis() < redisUnavailableUntil) {
            return;
        }
        try {
            String json = jsonMapper.writeValueAsString(feed);
            redissonClient.getBucket(key).set(json, Duration.ofMinutes(10));
        } catch (Exception e) {
            redisUnavailableUntil = System.currentTimeMillis() + REDIS_BACKOFF_MS;
            log.debug("write related cache failed: key={}", key, e);
        }
    }

    /**
     * 任意对象转 Long。
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
     * 任意对象转 Integer。
     */
    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 任意对象转字符串。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 任意对象转字符串列表。
     */
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = stringValue(item);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
