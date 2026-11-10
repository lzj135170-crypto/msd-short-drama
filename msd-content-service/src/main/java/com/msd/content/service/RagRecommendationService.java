package com.msd.content.service;

import com.msd.content.ai.AiExperimentService;
import com.msd.content.ai.AiInputGuard;
import com.msd.content.ai.AiMetrics;
import com.msd.content.ai.AiOutputGuard;
import com.msd.content.ai.AiStreamSanitizer;
import com.msd.content.ai.AiTokenBudgetService;
import com.msd.content.ai.RagResponseCache;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.AiStreamDelta;
import com.msd.content.dto.AiStreamDone;
import com.msd.content.dto.AiStreamMeta;
import com.msd.content.dto.AiStreamSources;
import com.msd.content.dto.RagRecommendRequest;
import com.msd.content.dto.RagRecommendResponse;
import com.msd.content.dto.RagSource;
import com.msd.content.dto.UserPreference;
import com.msd.content.rag.RagMetadata;
import com.msd.content.rag.RagTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 推荐编排：调用带 RetrievalAugmentationAdvisor 的 ChatClient，并组装来源。
 */
public class RagRecommendationService {

    // 日志
    private static final Logger log = LoggerFactory.getLogger(RagRecommendationService.class);

    /** 没有检索到任何短剧时的固定文案。 */
    public static final String NO_RESULT_ANSWER =
            "抱歉，暂时没有找到符合你描述的短剧，可以换一个说法再试试。";

    /** 有候选但生成失败时的固定文案。 */
    public static final String GENERATION_FALLBACK =
            "这几部短剧和你的需求比较接近，但暂时无法生成推荐理由，可以点开详情看看。";

    /** 单次推荐最多返回的短剧条数。 */
    private static final int MAX_TOP_K = 3;
    /** 正文摘要的截断长度（字符）。 */
    private static final int SNIPPET_CHARS = 120;
    /** 推荐回答最大长度。 */
    private static final int MAX_ANSWER_CHARS = 1500;
    /** 功能灰度关闭时的固定文案。 */
    static final String FEATURE_DISABLED_ANSWER =
            "AI 找剧暂未对当前用户开放，可以先用关键词搜索。";
    /** token 预算耗尽时的固定文案。 */
    static final String BUDGET_EXCEEDED_ANSWER =
            "今天的 AI 使用额度已用完，可以明天再试，或先用关键词搜索。";
    /** 功能灰度关闭标记。 */
    static final String FLAG_FEATURE_DISABLED = "feature_disabled";
    /** token 预算耗尽标记。 */
    static final String FLAG_BUDGET_EXCEEDED = "budget_exceeded";
    /** 预算估算时预留的系统提示字符数。 */
    private static final int BUDGET_SYSTEM_CHARS = 800;

    // RAG 专用 ChatClient，已挂载检索增强 advisor
    private final ChatClient ragChatClient;
    // RAG 超时、rerank 等配置
    private final RagProperties properties;
    // 用户偏好服务，测试中可为 null
    private final UserPreferenceService preferenceService;
    // 查询输入防护
    private final AiInputGuard inputGuard;
    // 回答输出防护
    private final AiOutputGuard outputGuard;
    // AI 链路指标
    private final AiMetrics metrics;
    // 功能灰度和 A/B 实验，测试可为 null
    private final AiExperimentService experimentService;
    // token 预算，测试可为 null
    private final AiTokenBudgetService budgetService;
    // 精确/语义缓存，测试可为 null
    private final RagResponseCache responseCache;

    /**
     * 兼容旧调用方的构造器，不加载用户偏好。
     */
    public RagRecommendationService(ChatClient ragChatClient, RagProperties properties) {
        this(ragChatClient, properties, null, new AiInputGuard(),
                new AiOutputGuard(), new AiMetrics(null));
    }

    /**
     * 注入 RAG ChatClient、配置和偏好服务。
     */
    public RagRecommendationService(ChatClient ragChatClient, RagProperties properties,
                                    UserPreferenceService preferenceService) {
        this(ragChatClient, properties, preferenceService, new AiInputGuard(),
                new AiOutputGuard(), new AiMetrics(null));
    }

    /**
     * 兼容旧调用方：输入防护可替换，输出防护使用默认实现。
     */
    public RagRecommendationService(ChatClient ragChatClient, RagProperties properties,
                                    UserPreferenceService preferenceService,
                                    AiInputGuard inputGuard) {
        this(ragChatClient, properties, preferenceService, inputGuard,
                new AiOutputGuard(), new AiMetrics(null));
    }

    /**
     * 注入完整依赖，便于测试替换输入/输出防护和指标。
     */
    public RagRecommendationService(ChatClient ragChatClient, RagProperties properties,
                                    UserPreferenceService preferenceService,
                                    AiInputGuard inputGuard,
                                    AiOutputGuard outputGuard,
                                    AiMetrics metrics) {
        this(ragChatClient, properties, preferenceService, inputGuard, outputGuard, metrics,
                null, null, null);
    }

    /**
     * 注入治理能力：灰度、预算和缓存。
     */
    public RagRecommendationService(ChatClient ragChatClient, RagProperties properties,
                                    UserPreferenceService preferenceService,
                                    AiInputGuard inputGuard,
                                    AiOutputGuard outputGuard,
                                    AiMetrics metrics,
                                    AiExperimentService experimentService,
                                    AiTokenBudgetService budgetService,
                                    RagResponseCache responseCache) {
        this.ragChatClient = ragChatClient;
        this.properties = properties;
        this.preferenceService = preferenceService;
        this.inputGuard = inputGuard;
        this.outputGuard = outputGuard;
        this.metrics = metrics;
        this.experimentService = experimentService;
        this.budgetService = budgetService;
        this.responseCache = responseCache;
    }

    /**
     * 执行一次不携带用户上下文的推荐。
     */
    public RagRecommendResponse recommend(RagRecommendRequest request) {
        return recommend(request, null);
    }

    /**
     * 执行一次推荐，可选加载用户偏好。
     */
    public RagRecommendResponse recommend(RagRecommendRequest request, Long userId) {
        String query = request.query().trim();
        inputGuard.validateQuery(query);
        int topK = resolveTopK(request.topK());
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();

        // 1. 先做功能灰度和实验分桶，关闭时不再消耗检索和模型资源
        AiExperimentService.Assignment assignment = assign(userId);
        recordExperiment(assignment);
        if (!featureEnabled(userId)) {
            return fixedResponse(FEATURE_DISABLED_ANSWER, FLAG_FEATURE_DISABLED,
                    requestId, startedAt, assignment, "rag");
        }

        // 2. trace 通过 advisor param 进入 Query.context，后续改写/检索/精排共享
        RagTrace trace = new RagTrace(query);
        trace.setRequestedTopK(topK);
        // 3. 实验变体同时作用于查询改写，降低低成本路径的模型开销
        trace.setRewriteModel(assignment != null && assignment.hasModel()
                ? assignment.model() : null);
        // 4. 可选加载画像，把偏好检索词交给多查询扩展器
        loadPreference(userId, trace);
        String fingerprint = cacheFingerprint(trace, topK, assignment);

        // 5. 精确/语义缓存命中时直接返回，不调用模型
        RagRecommendResponse cached = cacheGet(fingerprint, query, assignment);
        if (cached != null) {
            metrics.recordRequest("rag", false, List.of(),
                    System.currentTimeMillis() - startedAt);
            return cached;
        }

        // 6. 预占 token 预算；超限时固定降级，不发起检索
        AiTokenBudgetService.Reservation reservation = reserveBudget(userId, query, trace);
        if (reservation != null && !reservation.allowed()) {
            return fixedResponse(BUDGET_EXCEEDED_ANSWER, FLAG_BUDGET_EXCEEDED,
                    requestId, startedAt, assignment, "rag");
        }

        // 7. 记录实际输出长度，finally 中补记 token 和指标
        int[] outputChars = {0};
        try {
            ChatClient.ChatClientRequestSpec requestSpec = ragChatClient.prompt()
                    .user(query)
                    .advisors(advisor -> advisor.param(RagTrace.CONTEXT_KEY, trace));
            requestSpec = applyExperiment(requestSpec, assignment);
            ChatClientResponse response = requestSpec.call().chatClientResponse();

            String answer = extractAnswer(response);
            List<Document> documents = trace.getFinalDocuments();
            if (documents.isEmpty()) {
                documents = documentsFromResponse(response);
            }

            // 8. 没有检索到文档时固定返回 no-match，避免模型编造
            if (documents.isEmpty()) {
                outputChars[0] = answer == null ? NO_RESULT_ANSWER.length() : answer.length();
                return noResult(trace, requestId).withVariant(variant(assignment));
            }

            // 9. 生成失败但检索成功时保留来源，返回固定兜底文案
            if (answer == null || answer.isBlank()) {
                trace.markDegraded("generation");
                answer = GENERATION_FALLBACK;
            } else {
                answer = outputGuard.sanitize(answer.trim(), MAX_ANSWER_CHARS);
            }
            outputChars[0] = answer.length();
            RagRecommendResponse result = toResponse(answer, trace, documents, topK, requestId)
                    .withVariant(variant(assignment));
            cachePut(fingerprint, query, result);
            return result;
        } catch (Exception e) {
            log.error("rag recommend failed: requestId={}, queryChars={}",
                    requestId, query.length(), e);
            trace.markDegraded("generation");
            List<Document> documents = trace.getFinalDocuments();
            if (documents.isEmpty()) {
                outputChars[0] = NO_RESULT_ANSWER.length();
                return noResult(trace, requestId).withVariant(variant(assignment));
            }
            outputChars[0] = GENERATION_FALLBACK.length();
            return toResponse(GENERATION_FALLBACK, trace, documents, topK, requestId)
                    .withVariant(variant(assignment));
        } finally {
            settleBudget(reservation, query, trace, outputChars[0], variant(assignment));
            metrics.recordRequest("rag", trace.isDegraded(), trace.getDegradedFlags(),
                    System.currentTimeMillis() - startedAt);
        }
    }

    /**
     * 流式推荐。
     *
     * <p>事件顺序：meta → delta* → sources → done。检索为空时固定返回 no-match，
     * 不把模型可能编造的回答下发。</p>
     *
     * @param request 推荐请求
     * @param userId  当前用户，可为 null
     */
    public Flux<ServerSentEvent<Object>> stream(RagRecommendRequest request, Long userId) {
        String query = request.query().trim();
        inputGuard.validateQuery(query);
        int topK = resolveTopK(request.topK());
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();

        // 1. 功能灰度和实验分桶；关闭时直接下发固定文案
        AiExperimentService.Assignment assignment = assign(userId);
        recordExperiment(assignment);
        if (!featureEnabled(userId)) {
            return fixedStream(requestId, null, FEATURE_DISABLED_ANSWER, List.of(),
                    true, List.of(FLAG_FEATURE_DISABLED), startedAt);
        }

        // 2. trace 通过 advisor param 进入 Query.context，检索和精排共享
        RagTrace trace = new RagTrace(query);
        trace.setRequestedTopK(topK);
        trace.setRewriteModel(assignment != null && assignment.hasModel()
                ? assignment.model() : null);
        loadPreference(userId, trace);
        String fingerprint = cacheFingerprint(trace, topK, assignment);

        // 3. 缓存命中时不下发模型流
        RagRecommendResponse cached = cacheGet(fingerprint, query, assignment);
        if (cached != null) {
            return fixedStream(requestId, cached.rewrittenQuery(), cached.answer(),
                    cached.sources(), false, cached.degradedFlags(), startedAt);
        }

        // 4. 预占预算，拒绝时不再检索
        AiTokenBudgetService.Reservation reservation = reserveBudget(userId, query, trace);
        if (reservation != null && !reservation.allowed()) {
            return fixedStream(requestId, trace.getRewrittenQuery(), BUDGET_EXCEEDED_ANSWER,
                    List.of(), true, List.of(FLAG_BUDGET_EXCEEDED), startedAt);
        }
        StreamContext context = new StreamContext(query, fingerprint, variant(assignment),
                reservation, new StringBuilder());

        return Flux.create(sink -> {
            sink.next(event("meta", new AiStreamMeta(requestId, null, null,
                    trace.getRewrittenQuery())));
            AiStreamSanitizer sanitizer = new AiStreamSanitizer(outputGuard, MAX_ANSWER_CHARS);
            AtomicBoolean emitted = new AtomicBoolean(false);
            AtomicBoolean firstChunk = new AtomicBoolean(true);
            AtomicBoolean noDocuments = new AtomicBoolean(false);
            AtomicReference<Disposable> subscription = new AtomicReference<>();

            try {
                ChatClient.ChatClientRequestSpec requestSpec = ragChatClient.prompt()
                        .user(query)
                        .advisors(advisor -> advisor.param(RagTrace.CONTEXT_KEY, trace));
                requestSpec = applyExperiment(requestSpec, assignment);
                subscription.set(requestSpec.stream()
                        .content()
                        .subscribe(chunk -> {
                            if (sink.isCancelled() || chunk == null || chunk.isEmpty()) {
                                return;
                            }
                            if (firstChunk.compareAndSet(true, false)) {
                                metrics.recordFirstToken("rag",
                                        System.currentTimeMillis() - startedAt);
                                noDocuments.set(trace.getFinalDocuments().isEmpty());
                            }
                            // 5. 检索为空时丢弃模型回答，结束帧统一给固定文案
                            if (noDocuments.get()) {
                                return;
                            }
                            String safe = sanitizer.accept(chunk);
                            if (!safe.isEmpty()) {
                                emitted.set(true);
                                context.answer().append(safe);
                                sink.next(event("delta", new AiStreamDelta(safe)));
                            }
                        }, error -> {
                            log.error("rag stream failed: requestId={}, queryChars={}",
                                    requestId, query.length(), error);
                            trace.markDegraded("generation");
                            finishStream(sink, trace, sanitizer, emitted, topK, startedAt, context);
                        }, () -> finishStream(sink, trace, sanitizer, emitted, topK, startedAt,
                                context)));
            } catch (Exception e) {
                log.error("rag stream subscribe failed: requestId={}, queryChars={}",
                        requestId, query.length(), e);
                trace.markDegraded("generation");
                finishStream(sink, trace, sanitizer, emitted, topK, startedAt, context);
            }
            sink.onDispose(() -> {
                Disposable disposable = subscription.get();
                if (disposable != null) {
                    disposable.dispose();
                }
            });
        });
    }

    /**
     * 不调用模型的固定 SSE 响应。
     */
    private Flux<ServerSentEvent<Object>> fixedStream(String requestId, String rewrittenQuery,
                                                      String answer, List<RagSource> sources,
                                                      boolean degraded, List<String> flags,
                                                      long startedAt) {
        metrics.recordRequest("rag", degraded, flags, System.currentTimeMillis() - startedAt);
        return Flux.just(
                event("meta", new AiStreamMeta(requestId, null, null, rewrittenQuery)),
                event("delta", new AiStreamDelta(answer)),
                event("sources", new AiStreamSources<>(sources)),
                event("done", new AiStreamDone(degraded, flags,
                        System.currentTimeMillis() - startedAt, false)));
    }

    /**
     * 结束流式推荐：补齐兜底回答、来源、缓存和 done 帧。
     */
    private void finishStream(reactor.core.publisher.FluxSink<ServerSentEvent<Object>> sink,
                              RagTrace trace, AiStreamSanitizer sanitizer,
                              AtomicBoolean emitted, int topK, long startedAt,
                              StreamContext context) {
        if (sink.isCancelled()) {
            return;
        }
        List<Document> documents = trace.getFinalDocuments();
        List<String> flags = new ArrayList<>(trace.getDegradedFlags());

        // 1. 没有检索结果时固定返回 no-match，不采用模型输出
        if (documents.isEmpty()) {
            metrics.recordNoResult();
            sink.next(event("delta", new AiStreamDelta(NO_RESULT_ANSWER)));
            sink.next(event("sources", new AiStreamSources<>(List.of())));
            sink.next(event("done", new AiStreamDone(!flags.isEmpty(), List.copyOf(flags),
                    System.currentTimeMillis() - startedAt, false)));
            settleBudget(context.reservation(), context.query(), trace,
                    NO_RESULT_ANSWER.length(), context.variant());
            metrics.recordRequest("rag", !flags.isEmpty(), flags,
                    System.currentTimeMillis() - startedAt);
            sink.complete();
            return;
        }

        // 2. 输出剩余安全文本；完全没有内容时使用固定兜底文案
        String rest = sanitizer.finish();
        if (!rest.isEmpty()) {
            emitted.set(true);
            context.answer().append(rest);
            sink.next(event("delta", new AiStreamDelta(rest)));
        }
        if (!emitted.get()) {
            flags.add("generation");
            context.answer().append(GENERATION_FALLBACK);
            sink.next(event("delta", new AiStreamDelta(GENERATION_FALLBACK)));
        }
        List<RagSource> sources = documents.stream().limit(topK).map(this::toSource).toList();
        sink.next(event("sources", new AiStreamSources<>(sources)));
        sink.next(event("done", new AiStreamDone(!flags.isEmpty(), List.copyOf(flags),
                System.currentTimeMillis() - startedAt, false)));

        // 3. 未降级的完整回答写入缓存；token 按实际输出补记
        RagRecommendResponse response = new RagRecommendResponse(context.answer().toString(),
                trace.getRewrittenQuery(), sources, !flags.isEmpty(), flags,
                System.currentTimeMillis() - startedAt, UUID.randomUUID().toString(),
                false, context.variant());
        cachePut(context.fingerprint(), context.query(), response);
        settleBudget(context.reservation(), context.query(), trace,
                context.answer().length(), context.variant());
        metrics.recordRequest("rag", !flags.isEmpty(), flags,
                System.currentTimeMillis() - startedAt);
        sink.complete();
    }

    /**
     * 流式链路共享的缓存、预算和输出上下文。
     */
    private record StreamContext(String query, String fingerprint, String variant,
                                 AiTokenBudgetService.Reservation reservation,
                                 StringBuilder answer) {
    }

    /**
     * 构造 SSE 事件。
     */
    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.<Object>builder(data).event(name).build();
    }

    /**
     * 分配 RAG 模型实验变体。
     */
    private AiExperimentService.Assignment assign(Long userId) {
        return experimentService == null ? null : experimentService.assign("rag-model", userId);
    }

    /**
     * RAG 搜索功能是否对当前用户开放。
     */
    private boolean featureEnabled(Long userId) {
        return experimentService == null || experimentService.isEnabled("rag-search", userId);
    }

    /**
     * 记录实验分桶指标。
     */
    private void recordExperiment(AiExperimentService.Assignment assignment) {
        if (metrics != null && assignment != null) {
            metrics.recordExperiment(assignment.experiment(), assignment.variant());
        }
    }

    /**
     * 取当前实验变体名，未参与实验时为 null。
     */
    private String variant(AiExperimentService.Assignment assignment) {
        return assignment == null || !assignment.active() ? null : assignment.variant();
    }

    /**
     * 构造不调用模型的固定响应。
     */
    private RagRecommendResponse fixedResponse(String answer, String flag, String requestId,
                                               long startedAt,
                                               AiExperimentService.Assignment assignment,
                                               String scene) {
        long elapsedMs = System.currentTimeMillis() - startedAt;
        List<String> flags = List.of(flag);
        metrics.recordRequest(scene, true, flags, elapsedMs);
        return new RagRecommendResponse(answer, null, List.of(), true, flags, elapsedMs,
                requestId, false, variant(assignment));
    }

    /**
     * 缓存指纹：topK + 用户偏好检索词 + 实验变体，避免不同方案互相串结果。
     */
    private String cacheFingerprint(RagTrace trace, int topK,
                                    AiExperimentService.Assignment assignment) {
        String preference = trace.getPreferenceQuery();
        String safePreference = preference == null ? ""
                : preference.trim().toLowerCase(Locale.ROOT);
        return "k" + topK + "|" + safePreference + "|v" + variant(assignment);
    }

    /**
     * 查询缓存，异常时按未命中处理。
     */
    private RagRecommendResponse cacheGet(String fingerprint, String query,
                                          AiExperimentService.Assignment assignment) {
        if (responseCache == null) {
            return null;
        }
        try {
            RagRecommendResponse cached = responseCache.get(fingerprint, query).orElse(null);
            if (cached != null) {
                log.info("rag cache hit: variant={}, cached={}", variant(assignment), true);
            }
            return cached;
        } catch (Exception ex) {
            log.warn("rag cache get failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 写入缓存，异常时静默跳过。
     */
    private void cachePut(String fingerprint, String query, RagRecommendResponse response) {
        if (responseCache == null) {
            return;
        }
        try {
            responseCache.put(fingerprint, query, response);
        } catch (Exception ex) {
            log.warn("rag cache put failed: {}", ex.getMessage());
        }
    }

    /**
     * 预占 token 预算；预算服务缺失时返回 null。
     */
    private AiTokenBudgetService.Reservation reserveBudget(Long userId, String query,
                                                           RagTrace trace) {
        if (budgetService == null) {
            return null;
        }
        String preference = trace.getPreferenceQuery();
        int chars = query.length() + (preference == null ? 0 : preference.length())
                + BUDGET_SYSTEM_CHARS;
        try {
            return budgetService.reserve("rag", userId, chars);
        } catch (Exception ex) {
            log.warn("rag budget reserve failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 结算 token 预算并记录估算用量。
     */
    private void settleBudget(AiTokenBudgetService.Reservation reservation, String query,
                              RagTrace trace, int outputChars, String variant) {
        if (budgetService == null || reservation == null) {
            return;
        }
        String preference = trace.getPreferenceQuery();
        int inputChars = query.length() + (preference == null ? 0 : preference.length())
                + BUDGET_SYSTEM_CHARS;
        try {
            budgetService.settle(reservation, outputChars);
            metrics.recordTokens("rag", variant,
                    budgetService.estimate(inputChars, outputChars));
        } catch (Exception ex) {
            log.debug("rag budget settle failed: {}", ex.getMessage());
        }
    }

    /**
     * 按实验变体覆盖模型和温度。
     */
    private ChatClient.ChatClientRequestSpec applyExperiment(
            ChatClient.ChatClientRequestSpec requestSpec,
            AiExperimentService.Assignment assignment) {
        if (assignment == null || !assignment.hasModel()) {
            return requestSpec;
        }
        return requestSpec.options(DeepSeekChatOptions.builder()
                .model(assignment.model())
                .temperature(assignment.temperature()));
    }

    /**
     * 加载用户偏好；未登录、未开启或依赖失败时静默跳过。
     */
    private void loadPreference(Long userId, RagTrace trace) {
        // 1. 未登录、未开启或测试环境未注入偏好服务
        if (userId == null || userId <= 0 || preferenceService == null
                || !properties.getPersonalization().isEnabled()) {
            return;
        }
        try {
            // 2. 查询画像并写入 trace，供多查询扩展器读取
            UserPreference preference = preferenceService.get(userId);
            trace.setUserId(userId);
            trace.setPersonalizationEmpty(preference.empty());
            trace.setPreferenceQuery(preference.preferenceQuery());
            if (preference.degraded()) {
                trace.markDegraded("personalization");
            }
        } catch (Exception ex) {
            // 3. 画像失败不阻断推荐，只标记降级
            trace.markDegraded("personalization");
            log.warn("load user preference failed: userId={}", userId, ex);
        }
    }

    /**
     * 整体超时后的固定响应。
     */
    public RagRecommendResponse timeoutResponse() {
        metrics.recordRequest("rag", true, List.of("timeout"),
                properties.getTotalTimeoutMs());
        return new RagRecommendResponse(
                NO_RESULT_ANSWER,
                null,
                List.of(),
                true,
                List.of("timeout"),
                properties.getTotalTimeoutMs(),
                UUID.randomUUID().toString());
    }

    /**
     * 校验并收敛 topK；请求未传时使用 rerank.top-n。
     */
    private int resolveTopK(Integer requested) {
        int topK = requested == null ? properties.getRerank().getTopN() : requested;
        return Math.max(1, Math.min(topK, MAX_TOP_K));
    }

    /**
     * 从 ChatClientResponse 提取回答文本。
     */
    private String extractAnswer(ChatClientResponse response) {
        // 1. 响应或模型响应缺失时没有可提取的内容
        if (response == null || response.chatResponse() == null) {
            return null;
        }
        ChatResponse chatResponse = response.chatResponse();
        // 2. 输出节点缺失时同样返回 null
        if (chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        // 3. 取第一条输出的文本
        return chatResponse.getResult().getOutput().getText();
    }

    /**
     * 从 advisor 响应上下文兜底读取最终文档。
     */
    private List<Document> documentsFromResponse(ChatClientResponse response) {
        // 1. 响应上下文为空时视为没有文档
        if (response == null || response.context() == null) {
            return List.of();
        }
        // 2. advisor 把最终文档放在 DOCUMENT_CONTEXT 键下
        Object value = response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        // 3. 过滤非 Document 元素后返回不可变列表
        List<Document> documents = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Document document) {
                documents.add(document);
            }
        }
        return List.copyOf(documents);
    }

    /**
     * 组装正常响应。
     */
    private RagRecommendResponse toResponse(String answer, RagTrace trace,
                                            List<Document> documents, int topK,
                                            String requestId) {
        // 1. 截断到 topK 并逐条转成来源卡片
        List<RagSource> sources = documents.stream()
                .limit(topK)
                .map(this::toSource)
                .toList();
        // 2. 组装回答、改写查询、降级信息和请求标识
        return new RagRecommendResponse(
                answer,
                trace.getRewrittenQuery(),
                sources,
                trace.isDegraded(),
                trace.getDegradedFlags(),
                elapsed(trace),
                requestId);
    }

    /**
     * 无检索结果响应。
     */
    private RagRecommendResponse noResult(RagTrace trace, String requestId) {
        metrics.recordNoResult();
        return new RagRecommendResponse(
                NO_RESULT_ANSWER,
                trace.getRewrittenQuery(),
                List.of(),
                trace.isDegraded(),
                trace.getDegradedFlags(),
                elapsed(trace),
                requestId);
    }

    /**
     * Document 转来源卡片。
     */
    private RagSource toSource(Document document) {
        // metadata 字段逐个转换，缺失时使用 0 / null / 空串等默认值
        Map<String, Object> metadata = document.getMetadata();
        return new RagSource(
                longValue(metadata.get(RagMetadata.DRAMA_ID)),
                stringValue(metadata.get(RagMetadata.TITLE)),
                stringValue(metadata.get(RagMetadata.SUBTITLE)),
                stringValue(metadata.get(RagMetadata.COVER_URL)),
                snippet(document),
                firstDouble(
                        metadata.get(RagMetadata.RERANK_SCORE),
                        metadata.get(RagMetadata.RRF_SCORE),
                        document.getScore()),
                doubleValue(metadata.get(RagMetadata.RRF_SCORE)),
                integerValue(metadata.get(RagMetadata.VECTOR_RANK)),
                integerValue(metadata.get(RagMetadata.BM25_RANK)),
                retrievalSources(metadata));
    }

    /**
     * 优先取 metadata 摘要，缺失时截断正文。
     */
    private String snippet(Document document) {
        // 1. 优先使用 metadata 中预生成的摘要
        String snippet = stringValue(document.getMetadata().get(RagMetadata.SNIPPET));
        if (!snippet.isBlank()) {
            return snippet;
        }
        // 2. 摘要缺失时截断正文作为兜底
        String text = document.getText() == null ? "" : document.getText();
        return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS);
    }

    /**
     * 读取两路检索来源。
     */
    private List<String> retrievalSources(Map<String, Object> metadata) {
        // 1. 优先读取多路检索来源列表
        Object value = metadata.get(RagMetadata.RETRIEVAL_SOURCES);
        if (value instanceof List<?> values) {
            List<String> sources = new ArrayList<>();
            for (Object item : values) {
                String source = stringValue(item);
                if (!source.isBlank()) {
                    sources.add(source);
                }
            }
            if (!sources.isEmpty()) {
                return List.copyOf(sources);
            }
        }
        // 2. 兼容只记录单值来源的旧数据
        String source = stringValue(metadata.get(RagMetadata.RETRIEVAL_SOURCE));
        return source.isBlank() ? List.of() : List.of(source);
    }

    /**
     * 取第一个非空 Double。
     */
    private Double firstDouble(Object... values) {
        // 按传入顺序返回第一个可解析的数值
        for (Object value : values) {
            Double number = doubleValue(value);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    /**
     * 任意对象转 Long，无法转换时返回 0。
     */
    private Long longValue(Object value) {
        // 1. Number 类型直接取 long 值
        if (value instanceof Number number) {
            return number.longValue();
        }
        // 2. 字符串尝试解析，失败回退 0
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * 任意对象转 Integer，无法转换时返回 null。
     */
    private Integer integerValue(Object value) {
        // 1. Number 类型直接取 int 值
        if (value instanceof Number number) {
            return number.intValue();
        }
        // 2. 字符串尝试解析，失败返回 null
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
     * 任意对象转 Double，无法转换时返回 null。
     */
    private Double doubleValue(Object value) {
        // 1. Number 类型直接取 double 值
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        // 2. 字符串尝试解析，失败返回 null
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 任意对象转字符串，null 返回空字符串。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 计算耗时。
     */
    private long elapsed(RagTrace trace) {
        return Math.max(System.currentTimeMillis() - trace.getStartedAt(), 0);
    }
}
