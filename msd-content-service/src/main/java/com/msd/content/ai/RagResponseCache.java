package com.msd.content.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.msd.content.config.AiGovernanceProperties;
import com.msd.content.dto.RagRecommendResponse;
import com.msd.content.dto.RagSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG 精确缓存和语义缓存。
 *
 * <p>只缓存有来源、未降级的正常结果；语义缓存按用户偏好指纹隔离，
 * 向量不可用时静默跳过，不影响主链路。</p>
 */
public class RagResponseCache {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(RagResponseCache.class);

    // 治理配置
    private final AiGovernanceProperties properties;
    // 语义缓存使用的 embedding 模型，可为 null
    private final EmbeddingModel embeddingModel;
    // 缓存命中指标，可为 null
    private final AiMetrics metrics;
    // 精确缓存，key 为偏好指纹 + 归一化 query
    private final Cache<String, CachedValue> exactCache;
    // 语义缓存，key 为自增序号，value 带 query 向量和偏好指纹
    private final Cache<String, SemanticEntry> semanticCache;
    // 语义缓存序号
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 按配置创建精确和语义缓存。
     */
    public RagResponseCache(AiGovernanceProperties properties, EmbeddingModel embeddingModel) {
        this(properties, embeddingModel, null);
    }

    /**
     * 按配置创建缓存并记录命中指标。
     */
    public RagResponseCache(AiGovernanceProperties properties, EmbeddingModel embeddingModel,
                            AiMetrics metrics) {
        this.properties = properties;
        this.embeddingModel = embeddingModel;
        this.metrics = metrics;
        AiGovernanceProperties.Cache config = properties.getCache();
        this.exactCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(config.getExactMinutes(), 1)))
                .maximumSize(Math.max(config.getExactMaxEntries(), 1))
                .build();
        this.semanticCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(config.getSemanticMinutes(), 1)))
                .maximumSize(Math.max(config.getSemanticMaxEntries(), 1))
                .build();
    }

    /**
     * 查询缓存，先精确命中，再尝试语义命中。
     *
     * @param fingerprint 用户偏好指纹，匿名或空画像时为空串
     * @param query       用户原始查询
     */
    public Optional<RagRecommendResponse> get(String fingerprint, String query) {
        String safeFingerprint = fingerprint == null ? "" : fingerprint;
        // 1. 精确缓存
        if (properties.getCache().isExactEnabled()) {
            CachedValue exact = exactCache.getIfPresent(
                    exactKey(safeFingerprint, query));
            if (exact != null) {
                recordHit("exact");
                return Optional.of(toResponse(exact));
            }
        }
        // 2. 语义缓存只在与已有条目比较时才有意义
        if (!semanticEnabled() || semanticCache.estimatedSize() == 0) {
            return Optional.empty();
        }
        float[] vector = embed(query);
        if (vector == null) {
            return Optional.empty();
        }
        // 3. 只比较同一偏好指纹下的条目，取最高相似度
        SemanticEntry best = null;
        double bestScore = 0;
        for (SemanticEntry entry : semanticCache.asMap().values()) {
            if (!safeFingerprint.equals(entry.fingerprint())) {
                continue;
            }
            double score = cosine(vector, entry.vector());
            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        if (best != null && bestScore >= properties.getCache().getSemanticThreshold()) {
            recordHit("semantic");
            return Optional.of(toResponse(best.value()));
        }
        return Optional.empty();
    }

    /**
     * 写入缓存；降级、无来源或空回答不缓存。
     */
    public void put(String fingerprint, String query, RagRecommendResponse response) {
        if (response == null || response.degraded() || response.cached()
                || response.sources().isEmpty() || response.answer() == null
                || response.answer().isBlank()) {
            return;
        }
        String safeFingerprint = fingerprint == null ? "" : fingerprint;
        CachedValue value = new CachedValue(response.answer(), response.rewrittenQuery(),
                response.sources(), response.degradedFlags(), response.variant());
        // 1. 精确缓存
        if (properties.getCache().isExactEnabled()) {
            exactCache.put(exactKey(safeFingerprint, query), value);
        }
        // 2. 语义缓存需要 embedding；失败只跳过语义层
        if (semanticEnabled()) {
            float[] vector = embed(query);
            if (vector != null) {
                semanticCache.put("s-" + sequence.incrementAndGet(),
                        new SemanticEntry(safeFingerprint, vector, value));
            }
        }
    }

    /**
     * 清空缓存，供配置变更或测试使用。
     */
    public void clear() {
        exactCache.invalidateAll();
        semanticCache.invalidateAll();
    }

    /**
     * 语义缓存是否可用。
     */
    private boolean semanticEnabled() {
        return properties.getCache().isSemanticEnabled() && embeddingModel != null;
    }

    /**
     * 生成 embedding；任何异常都视为语义缓存不可用。
     */
    private float[] embed(String query) {
        try {
            float[] vector = embeddingModel.embed(query);
            return vector == null || vector.length == 0 ? null : vector;
        } catch (Exception ex) {
            log.debug("semantic cache embedding failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 精确缓存 key：偏好指纹 + 归一化查询。
     */
    private String exactKey(String fingerprint, String query) {
        return fingerprint + "|" + normalize(query);
    }

    /**
     * 归一化查询，忽略大小写和多余空白。
     */
    private String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    /**
     * 余弦相似度，向量为空时返回 0。
     */
    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /**
     * 缓存值转响应，替换 requestId 和耗时。
     */
    private RagRecommendResponse toResponse(CachedValue value) {
        return new RagRecommendResponse(value.answer(), value.rewrittenQuery(),
                value.sources(), false, value.degradedFlags(), 0,
                UUID.randomUUID().toString(), true, value.variant());
    }

    /**
     * 记录缓存命中指标。
     */
    private void recordHit(String type) {
        if (metrics != null) {
            metrics.recordCacheHit(type);
        }
    }

    /**
     * 缓存中的推荐内容。
     */
    private record CachedValue(String answer, String rewrittenQuery, List<RagSource> sources,
                               List<String> degradedFlags, String variant) {
    }

    /**
     * 语义缓存条目。
     */
    private record SemanticEntry(String fingerprint, float[] vector, CachedValue value) {
    }
}
