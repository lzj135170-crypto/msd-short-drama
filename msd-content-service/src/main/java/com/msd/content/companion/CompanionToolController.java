package com.msd.content.companion;

import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import com.msd.content.dto.CompanionContextRequest;
import com.msd.content.dto.CompanionContextView;
import com.msd.content.dto.EpisodeSummaryView;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * AgentScope 伴看工具接口，只允许服务间直连。
 *
 * <p>网关不路由 /internal/**，避免外部直接触发摘要生成。</p>
 */
@RestController
@RequestMapping("/internal/ai/companion/tools")
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionToolController {

    // 分集摘要服务
    private final EpisodeSummaryService summaryService;
    // 前情检索服务
    private final CompanionContextService contextService;

    /**
     * 注入分集摘要和前情检索服务。
     */
    public CompanionToolController(EpisodeSummaryService summaryService,
                                   CompanionContextService contextService) {
        this.summaryService = summaryService;
        this.contextService = contextService;
    }

    /**
     * 查询当前集摘要；剧集不可见时 data=null。
     */
    @GetMapping("/episodes/{episodeId}/summary")
    public Mono<Result<EpisodeSummaryView>> summary(
            @PathVariable("episodeId") Long episodeId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) Long userId) {
        // 1. 拒绝非法 ID，避免把无效参数传到 drama
        if (episodeId == null || episodeId <= 0) {
            return Mono.error(new IllegalArgumentException("episodeId 必须为正整数"));
        }
        // 2. 摘要可能触发模型调用，切到弹性线程池执行；
        // 剧集不可见时 getSummary 返回 null，必须包成 Result 再交给 Mono，否则响应体为空
        return Mono.fromCallable(() -> Result.ok(
                        summaryService.getSummaryForUser(episodeId, userId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 检索当前集之前的前情；剧集不可见时 data=null。
     */
    @PostMapping("/context")
    public Mono<Result<CompanionContextView>> context(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) Long userId,
            @Valid @RequestBody CompanionContextRequest request) {
        // 1. 检索包含向量、BM25 和模型调用，切到弹性线程池执行
        return Mono.fromCallable(() -> Result.ok(contextService.retrieve(
                        request.query(), request.currentEpisodeId(), request.topK(), userId)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
