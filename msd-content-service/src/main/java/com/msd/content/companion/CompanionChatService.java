package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.result.Result;
import com.msd.content.ai.AiInputGuard;
import com.msd.content.ai.AiMetrics;
import com.msd.content.ai.AiOutputGuard;
import com.msd.content.ai.AiStreamSanitizer;
import com.msd.content.client.DramaCompanionClient;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.AiStreamDelta;
import com.msd.content.dto.AiStreamDone;
import com.msd.content.dto.AiStreamError;
import com.msd.content.dto.AiStreamMeta;
import com.msd.content.dto.AiStreamSources;
import com.msd.content.dto.CompanionChatRequest;
import com.msd.content.dto.CompanionChatResponse;
import com.msd.content.dto.CompanionContextView;
import com.msd.content.dto.CompanionSource;
import com.msd.content.dto.ContextItem;
import com.msd.content.dto.EpisodeSummaryView;
import com.msd.content.dto.TranscriptCitation;
import com.msd.content.dto.TranscriptContextView;
import com.msd.content.memory.SummarizingChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 伴看聊天编排。
 *
 * <p>每轮注入当前集摘要和前情资料，带上会话记忆；任何依赖失败都降级回复，不把异常抛给前端。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionChatService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CompanionChatService.class);

    /** 生成失败时的固定回复。 */
    static final String GENERATION_FALLBACK =
            "抱歉，我暂时没能想好怎么回答，可以换个说法再聊聊这一集。";
    /** 剧集不可见时的固定回复。 */
    static final String NOT_FOUND_REPLY = "没有找到这一集，可能已经下线了。";
    /** 剧集服务不可用时的固定回复。 */
    static final String DRAMA_UNAVAILABLE_REPLY = "暂时无法读取剧集信息，稍后再试试。";
    /** 整体超时时的固定回复。 */
    static final String TIMEOUT_REPLY = "抱歉，这个回答花的时间有点久，可以再问我一次。";
    /** 付费集未解锁时的固定回复。 */
    static final String LOCKED_REPLY = "本集解锁后就可以一起聊剧情了。";
    /** 伴看回答最大长度。 */
    private static final int MAX_REPLY_CHARS = 1200;

    // 短剧内部接口客户端
    private final DramaCompanionClient dramaClient;
    // 分集摘要服务
    private final EpisodeSummaryService summaryService;
    // 前情检索服务
    private final CompanionContextService contextService;
    // 字幕片段检索服务，测试可传 null
    private final TranscriptContextService transcriptContextService;
    // 会话记忆
    private final SummarizingChatMemory chatMemory;
    // 伴看 ChatClient，已挂记忆 Advisor
    private final ChatClient companionChatClient;
    // 伴看配置
    private final CompanionProperties properties;
    // 付费集权限校验
    private final CompanionAccessService accessService;
    // 消息输入防护
    private final AiInputGuard inputGuard;
    // 回答输出防护
    private final AiOutputGuard outputGuard;
    // AI 链路指标
    private final AiMetrics metrics;

    /**
     * 注入聊天链路依赖、伴看 ChatClient、权限校验、字幕检索和 AI 防护。
     */
    @Autowired
    public CompanionChatService(DramaCompanionClient dramaClient,
                                EpisodeSummaryService summaryService,
                                CompanionContextService contextService,
                                TranscriptContextService transcriptContextService,
                                SummarizingChatMemory chatMemory,
                                @Qualifier("companionChatClient") ChatClient companionChatClient,
                                CompanionProperties properties,
                                CompanionAccessService accessService,
                                AiInputGuard inputGuard,
                                AiOutputGuard outputGuard,
                                AiMetrics metrics) {
        this.dramaClient = dramaClient;
        this.summaryService = summaryService;
        this.contextService = contextService;
        this.transcriptContextService = transcriptContextService;
        this.chatMemory = chatMemory;
        this.companionChatClient = companionChatClient;
        this.properties = properties;
        this.accessService = accessService;
        this.inputGuard = inputGuard;
        this.outputGuard = outputGuard;
        this.metrics = metrics;
    }

    /**
     * 兼容旧测试的构造器，不检索字幕片段。
     */
    public CompanionChatService(DramaCompanionClient dramaClient,
                                EpisodeSummaryService summaryService,
                                CompanionContextService contextService,
                                SummarizingChatMemory chatMemory,
                                ChatClient companionChatClient,
                                CompanionProperties properties,
                                CompanionAccessService accessService,
                                AiInputGuard inputGuard,
                                AiOutputGuard outputGuard,
                                AiMetrics metrics) {
        this(dramaClient, summaryService, contextService, null, chatMemory,
                companionChatClient, properties, accessService, inputGuard, outputGuard, metrics);
    }

    /**
     * 处理一轮伴看聊天。
     *
     * @param userId  网关注入的用户 ID
     * @param request 聊天请求
     */
    public CompanionChatResponse chat(Long userId, CompanionChatRequest request) {
        long startedAt = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        Set<String> flags = new LinkedHashSet<>();

        // 1. 先拦截明显的提示词注入和控制字符
        inputGuard.validateMessage(request.message());

        // 2. 读取当前剧集元信息，区分不可见和下游不可用
        CompanionEpisodeDTO meta;
        try {
            Result<CompanionEpisodeDTO> response = dramaClient.companion(request.episodeId());
            if (response == null || !response.isSuccess()) {
                flags.add(CompanionContextService.FLAG_DRAMA);
                return response(request, DRAMA_UNAVAILABLE_REPLY, flags, startedAt, requestId,
                        false, List.of());
            }
            meta = response.getData();
        } catch (Exception e) {
            log.warn("companion chat lookup failed: episodeId={}, error={}",
                    request.episodeId(), rootMessage(e));
            flags.add(CompanionContextService.FLAG_DRAMA);
            return response(request, DRAMA_UNAVAILABLE_REPLY, flags, startedAt, requestId,
                    false, List.of());
        }
        if (meta == null) {
            flags.add("not_found");
            return response(request, NOT_FOUND_REPLY, flags, startedAt, requestId, false, List.of());
        }

        // 3. 付费集必须在后端校验解锁状态，不能只依赖前端门禁
        if (!accessService.canAccess(userId, meta)) {
            flags.add("locked");
            log.info("companion chat locked: userId={}, episodeId={}", userId, meta.episodeId());
            return response(request, LOCKED_REPLY, flags, startedAt, requestId, true, List.of());
        }

        // 4. 当前集摘要，失败只标记降级
        EpisodeSummaryView summary = summaryService.getSummary(meta);
        if (summary == null || summary.degraded()) {
            flags.add("summary");
        }

        // 5. 当前集之前的前情，失败只标记降级
        CompanionContextView context = contextService.retrieve(
                request.message(), meta, properties.getContext().getDefaultTopK(), userId);
        if (context != null && context.degraded()) {
            flags.add("context");
        }
        // 5.1 播放位置之前的字幕片段，用于时间戳引用和更细的剧情问答
        TranscriptContextView transcriptContext = retrieveTranscriptContext(
                request, meta, userId);
        if (transcriptContext != null && transcriptContext.degraded()) {
            flags.add("transcript");
        }

        // 6. 会话 ID 包含用户、短剧和客户端会话，换集仍保持连续
        String conversationId = "u" + userId + ":d" + meta.dramaId()
                + ":s" + request.sessionId();
        String systemPrompt = buildSystemPrompt(meta, summary, context, transcriptContext);

        // 7. 调用带记忆 Advisor 的 ChatClient
        String reply = null;
        try {
            reply = companionChatClient.prompt()
                    .system(systemPrompt)
                    .user(request.message())
                    .advisors(advisor -> advisor.param(
                            ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("companion chat generation failed: episodeId={}, error={}",
                    meta.episodeId(), rootMessage(e));
            flags.add("generation");
        }
        if (reply == null || reply.isBlank()) {
            flags.add("generation");
            reply = GENERATION_FALLBACK;
        }
        // 8. 隐藏联系方式/PII 并限制回答长度
        reply = outputGuard.sanitize(reply, MAX_REPLY_CHARS);

        // 9. 收集记忆压缩/Redis 降级标记
        flags.addAll(chatMemory.drainDegradedFlags(conversationId));

        // 10. 附带当前集摘要、前情和字幕引用，前端可展开核对或 seek
        List<CompanionSource> sources = sources(summary, context, transcriptContext);
        log.info("companion chat: requestId={}, userId={}, episodeId={}, replyChars={}, "
                        + "flags={}, sources={}, elapsedMs={}",
                requestId, userId, meta.episodeId(), reply.length(), flags, sources.size(),
                System.currentTimeMillis() - startedAt);
        return response(request, reply, flags, startedAt, requestId, false, sources);
    }

    /**
     * 流式伴看聊天。
     *
     * <p>事件顺序：meta → delta* → sources → done；生成失败时追加 error 帧。
     * 记忆 Advisor 会在流结束后写入会话记忆。</p>
     *
     * @param userId  网关注入的用户 ID
     * @param request 聊天请求
     */
    public Flux<ServerSentEvent<Object>> stream(Long userId, CompanionChatRequest request) {
        long startedAt = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        Set<String> flags = new LinkedHashSet<>();

        // 1. 先拦截明显的提示词注入和控制字符
        try {
            inputGuard.validateMessage(request.message());
        } catch (Exception e) {
            metrics.recordRequest("companion", true, List.of("invalid_input"),
                    System.currentTimeMillis() - startedAt);
            return Flux.just(
                    event("meta", new AiStreamMeta(requestId, request.sessionId(),
                            request.episodeId(), "")),
                    event("error", new AiStreamError("invalid_input", "这条消息包含不支持的指令")),
                    event("done", new AiStreamDone(true, List.of("invalid_input"),
                            System.currentTimeMillis() - startedAt, false)));
        }

        // 2. 读取当前剧集元信息，区分不可见和下游不可用
        CompanionEpisodeDTO meta;
        try {
            Result<CompanionEpisodeDTO> response = dramaClient.companion(request.episodeId());
            if (response == null || !response.isSuccess()) {
                flags.add(CompanionContextService.FLAG_DRAMA);
                return directStream(request, requestId, DRAMA_UNAVAILABLE_REPLY, flags,
                        startedAt, false);
            }
            meta = response.getData();
        } catch (Exception e) {
            log.warn("companion stream lookup failed: episodeId={}, error={}",
                    request.episodeId(), rootMessage(e));
            flags.add(CompanionContextService.FLAG_DRAMA);
            return directStream(request, requestId, DRAMA_UNAVAILABLE_REPLY, flags,
                    startedAt, false);
        }
        if (meta == null) {
            flags.add("not_found");
            return directStream(request, requestId, NOT_FOUND_REPLY, flags, startedAt, false);
        }

        // 3. 付费集必须在后端校验解锁状态
        if (!accessService.canAccess(userId, meta)) {
            flags.add("locked");
            return directStream(request, requestId, LOCKED_REPLY, flags, startedAt, true);
        }

        // 4. 当前集摘要和前情，失败只标记降级
        EpisodeSummaryView summary = summaryService.getSummary(meta);
        if (summary == null || summary.degraded()) {
            flags.add("summary");
        }
        CompanionContextView context = contextService.retrieve(
                request.message(), meta, properties.getContext().getDefaultTopK(), userId);
        if (context != null && context.degraded()) {
            flags.add("context");
        }
        TranscriptContextView transcriptContext = retrieveTranscriptContext(
                request, meta, userId);
        if (transcriptContext != null && transcriptContext.degraded()) {
            flags.add("transcript");
        }
        List<CompanionSource> sources = sources(summary, context, transcriptContext);
        String conversationId = "u" + userId + ":d" + meta.dramaId()
                + ":s" + request.sessionId();
        String systemPrompt = buildSystemPrompt(meta, summary, context, transcriptContext);

        // 5. 订阅模型流，按安全边界逐段下发
        return Flux.create(sink -> {
            sink.next(event("meta", new AiStreamMeta(
                    requestId, request.sessionId(), request.episodeId(), "")));
            AiStreamSanitizer sanitizer = new AiStreamSanitizer(outputGuard, MAX_REPLY_CHARS);
            AtomicBoolean emitted = new AtomicBoolean(false);
            AtomicBoolean firstChunk = new AtomicBoolean(true);
            AtomicReference<Disposable> subscription = new AtomicReference<>();

            try {
                subscription.set(companionChatClient.prompt()
                        .system(systemPrompt)
                        .user(request.message())
                        .advisors(advisor -> advisor.param(
                                ChatMemory.CONVERSATION_ID, conversationId))
                        .stream()
                        .content()
                        .subscribe(chunk -> {
                            if (sink.isCancelled() || chunk == null || chunk.isEmpty()) {
                                return;
                            }
                            if (firstChunk.compareAndSet(true, false)) {
                                metrics.recordFirstToken("companion",
                                        System.currentTimeMillis() - startedAt);
                            }
                            String safe = sanitizer.accept(chunk);
                            if (!safe.isEmpty()) {
                                emitted.set(true);
                                sink.next(event("delta", new AiStreamDelta(safe)));
                            }
                        }, error -> {
                            flags.add("generation");
                            log.warn("companion stream generation failed: episodeId={}, error={}",
                                    meta.episodeId(), rootMessage(error));
                            sink.next(event("error", new AiStreamError(
                                    "generation", "本次回答生成失败，可以重试。")));
                            sink.next(event("sources", new AiStreamSources<>(sources)));
                            sink.next(event("done", new AiStreamDone(true,
                                    List.copyOf(flags),
                                    System.currentTimeMillis() - startedAt, false)));
                            metrics.recordRequest("companion", true, flags,
                                    System.currentTimeMillis() - startedAt);
                            sink.complete();
                        }, () -> {
                            String rest = sanitizer.finish();
                            if (!rest.isEmpty()) {
                                emitted.set(true);
                                sink.next(event("delta", new AiStreamDelta(rest)));
                            }
                            if (!emitted.get()) {
                                flags.add("generation");
                                sink.next(event("delta", new AiStreamDelta(GENERATION_FALLBACK)));
                            }
                            flags.addAll(chatMemory.drainDegradedFlags(conversationId));
                            sink.next(event("sources", new AiStreamSources<>(sources)));
                            sink.next(event("done", new AiStreamDone(!flags.isEmpty(),
                                    List.copyOf(flags),
                                    System.currentTimeMillis() - startedAt, false)));
                            metrics.recordRequest("companion", !flags.isEmpty(), flags,
                                    System.currentTimeMillis() - startedAt);
                            sink.complete();
                        }));
            } catch (Exception e) {
                flags.add("generation");
                log.warn("companion stream subscribe failed: episodeId={}, error={}",
                        meta.episodeId(), rootMessage(e));
                sink.next(event("error", new AiStreamError(
                        "generation", "本次回答生成失败，可以重试。")));
                sink.next(event("sources", new AiStreamSources<>(sources)));
                sink.next(event("done", new AiStreamDone(true, List.copyOf(flags),
                        System.currentTimeMillis() - startedAt, false)));
                metrics.recordRequest("companion", true, flags,
                        System.currentTimeMillis() - startedAt);
                sink.complete();
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
     * 无需调用模型的固定回复流。
     */
    private Flux<ServerSentEvent<Object>> directStream(CompanionChatRequest request,
                                                       String requestId, String reply,
                                                       Set<String> flags, long startedAt,
                                                       boolean locked) {
        metrics.recordRequest("companion", !flags.isEmpty(), flags,
                System.currentTimeMillis() - startedAt);
        return Flux.just(
                event("meta", new AiStreamMeta(requestId, request.sessionId(),
                        request.episodeId(), "")),
                event("delta", new AiStreamDelta(reply)),
                event("done", new AiStreamDone(!flags.isEmpty(), List.copyOf(flags),
                        System.currentTimeMillis() - startedAt, locked)));
    }

    /**
     * 构造 SSE 事件。
     */
    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.<Object>builder(data).event(name).build();
    }

    /**
     * 整体超时后的固定响应。
     */
    public CompanionChatResponse timeoutResponse(CompanionChatRequest request) {
        long timeoutMs = properties.getChat().getTimeoutMs();
        metrics.recordRequest("companion", true, java.util.List.of("timeout"), timeoutMs);
        return new CompanionChatResponse(
                TIMEOUT_REPLY, request.sessionId(), request.episodeId(),
                true, java.util.List.of("timeout"), timeoutMs,
                UUID.randomUUID().toString(), false);
    }

    /**
     * 检索当前播放位置之前的字幕；失败只返回 null，不阻断聊天。
     */
    private TranscriptContextView retrieveTranscriptContext(CompanionChatRequest request,
                                                            CompanionEpisodeDTO meta,
                                                            Long userId) {
        if (transcriptContextService == null) {
            return null;
        }
        try {
            return transcriptContextService.retrieve(request.message(), meta,
                    request.positionMs(), properties.getContext().getDefaultTopK(), userId);
        } catch (Exception e) {
            log.warn("companion transcript context failed: episodeId={}, error={}",
                    meta.episodeId(), rootMessage(e));
            return null;
        }
    }

    /**
     * 构造带当前集摘要、前情和字幕资料的系统提示。
     */
    private String buildSystemPrompt(CompanionEpisodeDTO meta,
                                     EpisodeSummaryView summary,
                                     CompanionContextView context,
                                     TranscriptContextView transcriptContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是「码上短剧」的 AI 伴看助手，只陪用户讨论当前正在观看的这一集及其之前的情节。\n\n");

        // 1. 当前剧集事实
        prompt.append("【当前剧集】\n")
                .append("剧名：").append(nullToEmpty(meta.dramaTitle())).append('\n')
                .append("集数：第").append(meta.episodeNo()).append("集 ")
                .append(nullToEmpty(meta.episodeTitle())).append('\n');
        if (summary != null && hasText(summary.summary())) {
            prompt.append("当前集摘要：").append(summary.summary()).append('\n');
        } else {
            prompt.append("当前集摘要：暂不可用\n");
        }

        // 2. 当前集之前的前情
        prompt.append("\n【前情资料】\n");
        if (context != null && !context.items().isEmpty()) {
            int index = 1;
            for (ContextItem item : context.items()) {
                prompt.append('[').append(index++).append("] 第")
                        .append(item.episodeNo()).append("集 ")
                        .append(nullToEmpty(item.episodeTitle())).append("：")
                        .append(nullToEmpty(item.summary())).append('\n');
            }
        } else {
            prompt.append("没有检索到当前集之前的前情。\n");
        }

        // 3. 当前播放位置之前的字幕片段，带时间戳供引用
        prompt.append("\n【字幕片段（仅当前播放位置之前）】\n");
        if (transcriptContext != null && !transcriptContext.items().isEmpty()) {
            int index = 1;
            for (TranscriptCitation item : transcriptContext.items()) {
                prompt.append('[').append(index++).append("] 第")
                        .append(item.episodeNo()).append("集 ")
                        .append(timestamp(item.startMs())).append(' ')
                        .append(item.text()).append('\n');
            }
        } else {
            prompt.append("没有检索到可用的字幕片段。\n");
        }

        // 4. 防剧透和输出要求
        prompt.append("\n【规则】\n")
                .append("1. 只能使用当前集摘要、前情和字幕片段回答，不得编造资料之外的信息。\n")
                .append("2. 严禁提及、猜测或暗示第").append(meta.episodeNo())
                .append("集之后的任何剧情、结局或反转。\n")
                .append("3. 用户追问后续时，统一回复：“为了不剧透，我只陪你看到当前这一集。”\n")
                .append("4. 回答 2-4 句，中文口语化；引用字幕时使用 [第X集 mm:ss] 格式。\n")
                .append("5. 资料不足时直接说明，不要编造。\n");
        return prompt.toString();
    }

    /**
     * 毫秒转 mm:ss。
     */
    private String timestamp(Integer millis) {
        int value = millis == null ? 0 : Math.max(millis, 0);
        return String.format("%02d:%02d", value / 60_000, (value % 60_000) / 1000);
    }

    /**
     * 组装响应并记录耗时。
     */
    private CompanionChatResponse response(CompanionChatRequest request, String reply,
                                           Set<String> flags, long startedAt,
                                           String requestId, boolean locked,
                                           List<CompanionSource> sources) {
        long elapsedMs = System.currentTimeMillis() - startedAt;
        metrics.recordRequest("companion", !flags.isEmpty(), flags, elapsedMs);
        return new CompanionChatResponse(
                reply,
                request.sessionId(),
                request.episodeId(),
                !flags.isEmpty(),
                java.util.List.copyOf(flags),
                elapsedMs,
                requestId,
                locked,
                sources);
    }

    /**
     * 组装回答引用：当前集摘要在前，前情和字幕引用在后。
     */
    List<CompanionSource> sources(EpisodeSummaryView summary, CompanionContextView context,
                                  TranscriptContextView transcriptContext) {
        List<CompanionSource> sources = new ArrayList<>();
        // 1. 当前集摘要单独标记为 current
        if (summary != null && hasText(summary.summary())) {
            sources.add(new CompanionSource("current", summary.episodeId(), summary.episodeNo(),
                    summary.episodeTitle(), summary.summary()));
        }
        // 2. 前情按检索顺序追加，最多 4 条
        if (context != null && context.items() != null) {
            for (ContextItem item : context.items()) {
                if (sources.size() >= 4) {
                    break;
                }
                sources.add(new CompanionSource("previous", item.episodeId(), item.episodeNo(),
                        item.episodeTitle(), item.summary()));
            }
        }
        // 3. 字幕片段带时间戳，前端可点击 seek
        if (transcriptContext != null && transcriptContext.items() != null) {
            for (TranscriptCitation item : transcriptContext.items()) {
                if (sources.size() >= 7) {
                    break;
                }
                sources.add(new CompanionSource("transcript", item.episodeId(),
                        item.episodeNo(), item.episodeTitle(), item.text(),
                        item.startMs(), item.endMs()));
            }
        }
        return List.copyOf(sources);
    }

    /**
     * 判断字符串是否包含非空白字符。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * null 转为空字符串。
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 取最内层异常信息。
     */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
