package com.msd.content.companion;

import com.msd.common.result.Result;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.AiStreamDone;
import com.msd.content.dto.AiStreamError;
import com.msd.content.dto.CompanionChatRequest;
import com.msd.content.dto.CompanionChatResponse;
import com.msd.content.dto.RecapView;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * AI 伴看聊天接口，经网关注入用户身份。
 */
@RestController
@RequestMapping("/internal/ai/companion")
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionChatController {

    // 前情提要读取超时
    private static final Duration RECAP_TIMEOUT = Duration.ofSeconds(5);

    // 伴看聊天服务
    private final CompanionChatService chatService;
    // 前情提要服务
    private final CompanionRecapService recapService;
    // 伴看配置，提供整体超时
    private final CompanionProperties properties;

    /**
     * 注入聊天、前情提要和伴看配置。
     */
    public CompanionChatController(CompanionChatService chatService,
                                   CompanionRecapService recapService,
                                   CompanionProperties properties) {
        this.chatService = chatService;
        this.recapService = recapService;
        this.properties = properties;
    }

    /**
     * 处理一轮伴看聊天；整体超时返回固定降级回复。
     */
    @PostMapping("/chat")
    public Mono<Result<CompanionChatResponse>> chat(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Valid @RequestBody CompanionChatRequest request) {
        // 1. 缺少网关注入的用户身份时拒绝，避免会话记忆串号
        if (userId == null || userId <= 0) {
            return Mono.error(new IllegalArgumentException("缺少用户身份"));
        }
        // 2. 聊天包含远程检索和模型调用，切到弹性线程池执行
        return Mono.fromCallable(() -> Result.ok(chatService.chat(userId, request)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(Math.max(properties.getChat().getTimeoutMs(), 1)))
                // 3. 整体超时不抛错，返回固定兜底回复
                .onErrorResume(TimeoutException.class,
                        e -> Mono.just(Result.ok(chatService.timeoutResponse(request))));
    }

    /**
     * 流式伴看聊天，事件顺序为 meta → delta* → sources → done。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatStream(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Valid @RequestBody CompanionChatRequest request) {
        // 1. 缺少网关注入的用户身份时拒绝，避免会话记忆串号
        if (userId == null || userId <= 0) {
            return Flux.error(new IllegalArgumentException("缺少用户身份"));
        }
        long timeoutMs = Math.max(properties.getChat().getTimeoutMs(), 1);
        // 2. 摘要和前情读取会阻塞，放到弹性线程池后再订阅模型流
        return Mono.fromCallable(() -> chatService.stream(userId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(flux -> flux)
                // 3. 流式生成整体超时；已输出的部分保留，追加 timeout 错误帧
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(TimeoutException.class, e -> Flux.just(
                        event("error", new AiStreamError("timeout",
                                "这次回答花的时间有点久，可以重试")),
                        event("done", new AiStreamDone(true, List.of("timeout"),
                                timeoutMs, false))));
    }

    /**
     * 查询当前集及之前的前情提要。
     *
     * <p>未登录用户只返回免费集；付费集权限在后端统一校验。</p>
     */
    @GetMapping("/recap")
    public Mono<Result<RecapView>> recap(
            @RequestParam("dramaId") Long dramaId,
            @RequestParam("episodeNo") Integer episodeNo,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        // 1. 前情依赖 drama 和 order，切到弹性线程池执行
        return Mono.fromCallable(() -> recapService.getRecap(dramaId, episodeNo, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(RECAP_TIMEOUT)
                .map(Result::ok)
                // 2. 前情失败不影响播放，返回空降级结果
                .onErrorResume(Exception.class, e -> Mono.just(Result.ok(
                        new RecapView(dramaId, episodeNo, null, java.util.List.of(),
                                true, java.util.List.of("drama")))));
    }

    /**
     * 构造 SSE 事件。
     */
    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.<Object>builder(data).event(name).build();
    }
}
