package com.msd.content.config;

import com.msd.content.ai.AiExperimentService;
import com.msd.content.ai.AiInputGuard;
import com.msd.content.ai.AiMetrics;
import com.msd.content.ai.AiOutputGuard;
import com.msd.content.ai.AiTokenBudgetService;
import com.msd.content.ai.RagResponseCache;
import com.msd.content.rag.DashScopeRerankPostProcessor;
import com.msd.content.rag.ElasticsearchBm25Store;
import com.msd.content.rag.HybridRetriever;
import com.msd.content.rag.LlmMultiQueryExpander;
import com.msd.content.rag.LlmQueryTransformer;
import com.msd.content.rag.RrfDocumentJoiner;
import com.msd.content.rag.RrfMerger;
import com.msd.content.service.RagRecommendationService;
import com.msd.content.service.UserPreferenceService;
import com.msd.content.support.LazyMilvusStoreProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * RAG 链路装配：ChatClient、查询改写、混合检索、Rerank、Advisor 和线程池。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RagConfig {

    /** 推荐助手系统提示，约束只基于检索资料回答。 */
    private static final String RECOMMEND_SYSTEM_PROMPT = """
            你是“码上短剧”的推荐助手。只能依据【短剧资料】回答，不能编造资料之外的剧名、演员、剧情或数据。
            从资料中挑选最匹配的短剧，每部用一行，格式：《剧名》——推荐理由（1-2 句，必须能在资料中找到依据）。
            如果资料为空或都不匹配，直接说明没有找到匹配的短剧，不要推荐资料外的内容。
            回答使用简体中文，简洁直接。
            """;

    /** 有上下文时的增强提示。 */
    private static final String CONTEXT_PROMPT = """
            【短剧资料】
            {context}

            【用户需求】
            {query}

            请基于以上资料给出推荐，最多推荐 3 部。每部必须给出剧名和 1-2 句有资料依据的推荐理由。
            不要推荐资料之外的短剧，不要编造演员、剧情或数据。
            """;

    /**
     * 空上下文提示。
     *
     * <p>ContextualQueryAugmenter 对空上下文模板做无参渲染，这里不能包含 {query} 占位符。</p>
     */
    private static final String EMPTY_CONTEXT_PROMPT = """
            没有检索到匹配的短剧资料。
            请直接回复：抱歉，暂时没有找到符合你描述的短剧，可以换一个说法再试试。
            不要编造任何剧名。
            """;

    /**
     * 查询改写专用 ChatClient，不挂 RAG advisor。
     */
    @Bean("rewriteChatClient")
    public ChatClient rewriteChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.clone()
                .defaultSystem(LlmQueryTransformer.SYSTEM_PROMPT)
                .build();
    }

    /**
     * 多查询扩展专用 ChatClient，使用独立提示，避免复用单行改写器。
     */
    @Bean("expandChatClient")
    public ChatClient expandChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.clone()
                .defaultSystem(LlmMultiQueryExpander.SYSTEM_PROMPT)
                .build();
    }

    /**
     * 推荐 ChatClient，默认挂 RetrievalAugmentationAdvisor。
     */
    @Bean("ragChatClient")
    public ChatClient ragChatClient(ChatClient.Builder chatClientBuilder,
                                    RetrievalAugmentationAdvisor advisor) {
        return chatClientBuilder.clone()
                .defaultSystem(RECOMMEND_SYSTEM_PROMPT)
                .defaultAdvisors(advisor)
                .build();
    }

    /**
     * 查询改写器。
     */
    @Bean
    public LlmQueryTransformer llmQueryTransformer(
            @Qualifier("rewriteChatClient") ChatClient rewriteChatClient,
            RagProperties properties,
            @Qualifier("ragRewriteExecutor") ThreadPoolTaskExecutor rewriteExecutor) {
        return new LlmQueryTransformer(rewriteChatClient, properties, rewriteExecutor);
    }

    /**
     * 多查询扩展器。
     */
    @Bean
    public LlmMultiQueryExpander llmMultiQueryExpander(
            @Qualifier("expandChatClient") ChatClient expandChatClient,
            RagProperties properties,
            @Qualifier("ragRewriteExecutor") ThreadPoolTaskExecutor rewriteExecutor) {
        return new LlmMultiQueryExpander(expandChatClient, properties, rewriteExecutor);
    }

    /**
     * 跨查询 RRF 融合器。
     */
    @Bean
    public RrfDocumentJoiner rrfDocumentJoiner(RagProperties properties) {
        return new RrfDocumentJoiner(properties);
    }

    /**
     * 混合检索器。
     */
    @Bean
    public HybridRetriever hybridRetriever(
            RagProperties properties,
            LazyMilvusStoreProvider milvusProvider,
            ElasticsearchBm25Store bm25Store,
            RrfMerger rrfMerger,
            @Qualifier("ragRetrievalExecutor") ThreadPoolTaskExecutor retrievalExecutor) {
        return new HybridRetriever(properties, milvusProvider, bm25Store, rrfMerger, retrievalExecutor);
    }

    /**
     * DashScope 精排器。
     */
    @Bean
    public DashScopeRerankPostProcessor dashScopeRerankPostProcessor(
            RagProperties properties,
            JsonMapper jsonMapper,
            @Qualifier("rerankHttpClient") HttpClient rerankHttpClient) {
        return new DashScopeRerankPostProcessor(properties, jsonMapper, rerankHttpClient);
    }

    /**
     * 上下文增强器，空上下文时走固定 no-match 提示。
     */
    @Bean
    public ContextualQueryAugmenter contextualQueryAugmenter() {
        return ContextualQueryAugmenter.builder()
                .promptTemplate(new PromptTemplate(CONTEXT_PROMPT))
                .emptyContextPromptTemplate(new PromptTemplate(EMPTY_CONTEXT_PROMPT))
                .allowEmptyContext(false)
                .build();
    }

    /**
     * RAG Advisor：改写 -> 混合检索 -> Rerank -> 上下文增强。
     */
    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            RagProperties properties,
            LlmQueryTransformer queryTransformer,
            LlmMultiQueryExpander multiQueryExpander,
            RrfDocumentJoiner documentJoiner,
            HybridRetriever hybridRetriever,
            DashScopeRerankPostProcessor rerankPostProcessor,
            @Qualifier("ragAdvisorExecutor") ThreadPoolTaskExecutor advisorExecutor) {
        // 1. 组装检索、跨查询融合、精排、上下文增强和执行线程池
        RetrievalAugmentationAdvisor.Builder builder = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(hybridRetriever)
                .documentJoiner(documentJoiner)
                .documentPostProcessors(rerankPostProcessor)
                .queryAugmenter(contextualQueryAugmenter())
                .taskExecutor(advisorExecutor);
        // 2. 仅在改写开启时挂载 query transformer
        if (properties.getRewrite().isEnabled()) {
            builder.queryTransformers(queryTransformer);
        }
        // 3. 仅在多查询开启时挂载 query expander
        if (properties.getExpansion().isEnabled()) {
            builder.queryExpander(multiQueryExpander);
        }
        return builder.build();
    }

    /**
     * 推荐编排服务。
     */
    @Bean
    public RagRecommendationService ragRecommendationService(
            @Qualifier("ragChatClient") ChatClient ragChatClient,
            RagProperties properties,
            UserPreferenceService preferenceService,
            AiInputGuard inputGuard,
            AiOutputGuard outputGuard,
            AiMetrics metrics,
            AiExperimentService experimentService,
            AiTokenBudgetService budgetService,
            RagResponseCache responseCache) {
        return new RagRecommendationService(ragChatClient, properties, preferenceService,
                inputGuard, outputGuard, metrics, experimentService, budgetService,
                responseCache);
    }

    /**
     * Advisor 执行线程池；与检索线程池分离，避免嵌套等待死锁。
     */
    @Bean("ragAdvisorExecutor")
    public ThreadPoolTaskExecutor ragAdvisorExecutor() {
        return executor(4, 16, 100, "rag-advisor-");
    }

    /**
     * 两路并行检索线程池。
     */
    @Bean("ragRetrievalExecutor")
    public ThreadPoolTaskExecutor ragRetrievalExecutor() {
        return executor(8, 16, 128, "rag-retrieval-");
    }

    /**
     * 查询改写线程池。
     */
    @Bean("ragRewriteExecutor")
    public ThreadPoolTaskExecutor ragRewriteExecutor() {
        return executor(2, 4, 32, "rag-rewrite-");
    }

    /**
     * 索引重建线程池，串行执行避免并发写。
     */
    @Bean("ragIndexExecutor")
    public ThreadPoolTaskExecutor ragIndexExecutor() {
        return executor(1, 1, 4, "rag-index-");
    }

    /**
     * Rerank HTTP 客户端，连接超时不超过请求超时。
     */
    @Bean("rerankHttpClient")
    public HttpClient rerankHttpClient(RagProperties properties) {
        long connectTimeoutMs = Math.max(
                Math.min(properties.getRerank().getTimeoutMs(), 2_000), 100);
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    /**
     * 创建统一配置的线程池。
     */
    private ThreadPoolTaskExecutor executor(int coreSize, int maxSize, int queueCapacity,
                                            String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
