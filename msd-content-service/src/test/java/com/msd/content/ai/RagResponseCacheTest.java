package com.msd.content.ai;

import com.msd.content.config.AiGovernanceProperties;
import com.msd.content.dto.RagRecommendResponse;
import com.msd.content.dto.RagSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG 精确缓存和语义缓存测试。
 */
class RagResponseCacheTest {

    /**
     * 相同查询命中精确缓存，请求 ID 重新生成。
     */
    @Test
    void exactCacheReturnsNewRequestId() {
        AiGovernanceProperties properties = properties();
        RagResponseCache cache = new RagResponseCache(properties, null);
        RagRecommendResponse original = response("原回答");

        cache.put("pref", "先婚后爱", original);
        RagRecommendResponse cached = cache.get("pref", "先婚后爱").orElseThrow();

        assertThat(cached.answer()).isEqualTo("原回答");
        assertThat(cached.cached()).isTrue();
        assertThat(cached.requestId()).isNotEqualTo(original.requestId());
        assertThat(cached.sources()).hasSize(1);
    }

    /**
     * 不同偏好指纹不能共用缓存，避免跨用户画像串结果。
     */
    @Test
    void fingerprintSeparatesCache() {
        RagResponseCache cache = new RagResponseCache(properties(), null);
        cache.put("user-a", "先婚后爱", response("回答"));

        assertThat(cache.get("user-b", "先婚后爱")).isEmpty();
    }

    /**
     * 语义相近的查询命中语义缓存。
     */
    @Test
    void semanticCacheHitsSimilarQuery() {
        AiGovernanceProperties properties = properties();
        properties.getCache().setSemanticEnabled(true);
        properties.getCache().setSemanticThreshold(0.9);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1f, 0f});
        RagResponseCache cache = new RagResponseCache(properties, embeddingModel);
        cache.put("pref", "先婚后爱的甜宠剧", response("甜宠推荐"));

        RagRecommendResponse cached = cache.get("pref", "契约婚姻甜宠剧").orElseThrow();

        assertThat(cached.answer()).isEqualTo("甜宠推荐");
        assertThat(cached.cached()).isTrue();
    }

    /**
     * 语义向量不相似时不命中。
     */
    @Test
    void semanticCacheRejectsDifferentVector() {
        AiGovernanceProperties properties = properties();
        properties.getCache().setSemanticEnabled(true);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("先婚后爱的甜宠剧")).thenReturn(new float[]{1f, 0f});
        when(embeddingModel.embed("悬疑刑侦剧")).thenReturn(new float[]{0f, 1f});
        RagResponseCache cache = new RagResponseCache(properties, embeddingModel);
        cache.put("pref", "先婚后爱的甜宠剧", response("甜宠推荐"));

        assertThat(cache.get("pref", "悬疑刑侦剧")).isEmpty();
    }

    /**
     * 降级结果不进入缓存，避免把失败答案固化。
     */
    @Test
    void degradedResponseIsNotCached() {
        RagResponseCache cache = new RagResponseCache(properties(), null);
        RagRecommendResponse degraded = new RagRecommendResponse(
                "兜底", null, List.of(), true, List.of("generation"), 10, "req");

        cache.put("pref", "query", degraded);

        assertThat(cache.get("pref", "query")).isEmpty();
    }

    /**
     * 构造默认缓存配置。
     */
    private AiGovernanceProperties properties() {
        return new AiGovernanceProperties();
    }

    /**
     * 构造带一个来源的正常响应。
     */
    private RagRecommendResponse response(String answer) {
        return new RagRecommendResponse(
                answer, "rewritten",
                List.of(new RagSource(5001L, "隐婚总裁的甜妻", "先婚后爱", "",
                        "来源摘要", 0.9, 0.03, 1, 2, List.of("vector", "bm25"))),
                false, List.of(), 10, "req-" + answer);
    }
}
