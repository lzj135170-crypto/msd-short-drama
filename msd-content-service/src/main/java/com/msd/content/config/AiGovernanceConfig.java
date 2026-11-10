package com.msd.content.config;

import com.msd.content.ai.AiExperimentService;
import com.msd.content.ai.AiMetrics;
import com.msd.content.ai.AiTokenBudgetService;
import com.msd.content.ai.RagResponseCache;
import org.redisson.api.RedissonClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * AI 治理能力装配：灰度实验、token 预算和 RAG 缓存。
 */
@Configuration
@EnableConfigurationProperties(AiGovernanceProperties.class)
public class AiGovernanceConfig {

    /**
     * 功能灰度和 A/B 实验分桶。
     */
    @Bean
    public AiExperimentService aiExperimentService(AiGovernanceProperties properties) {
        return new AiExperimentService(properties);
    }

    /**
     * 按用户按天的 token 预算；Redis 首次使用时才连接。
     */
    @Bean
    public AiTokenBudgetService aiTokenBudgetService(
            @Lazy RedissonClient redissonClient, AiGovernanceProperties properties) {
        return new AiTokenBudgetService(redissonClient, properties);
    }

    /**
     * RAG 精确/语义缓存；没有 embedding 模型时只启用精确缓存。
     */
    @Bean
    public RagResponseCache ragResponseCache(AiGovernanceProperties properties,
                                             ObjectProvider<EmbeddingModel> embeddingModels,
                                             AiMetrics metrics) {
        return new RagResponseCache(properties, embeddingModels.getIfAvailable(), metrics);
    }
}
