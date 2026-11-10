package com.msd.content.companion;

import com.msd.common.result.Result;
import com.msd.content.dto.SummaryWarmupResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 分集摘要批量预热运维接口。
 *
 * <p>只允许直连 8107，网关不路由；批量生成可能耗时，调用方需要设置较长超时。</p>
 */
@RestController
@RequestMapping("/internal/ai/companion/summaries")
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionSummaryWarmupController {

    // 摘要预热服务
    private final EpisodeSummaryWarmupService warmupService;

    /**
     * 注入摘要预热服务。
     */
    public CompanionSummaryWarmupController(EpisodeSummaryWarmupService warmupService) {
        this.warmupService = warmupService;
    }

    /**
     * 为指定短剧批量补齐缺失摘要。
     */
    @PostMapping("/warmup")
    public Mono<Result<SummaryWarmupResult>> warmup(
            @RequestParam("dramaId") Long dramaId,
            @RequestParam(value = "limit", defaultValue = "10") Integer limit) {
        // 1. Feign、模型和回写都在 boundedElastic 执行，避免阻塞 WebFlux 事件线程
        return Mono.fromCallable(() -> Result.ok(warmupService.warmup(dramaId, limit)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
