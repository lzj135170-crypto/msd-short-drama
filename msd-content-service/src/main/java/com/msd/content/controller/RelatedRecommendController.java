package com.msd.content.controller;

import com.msd.common.result.Result;
import com.msd.content.dto.RelatedFeed;
import com.msd.content.service.RelatedRecommendService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 相似短剧接口，经网关访问。
 */
@RestController
@RequestMapping("/internal/ai/rag")
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RelatedRecommendController {

    /** 相关推荐整体超时，避免详情页被向量检索拖住。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    // 相似短剧服务
    private final RelatedRecommendService relatedService;

    /**
     * 注入相似短剧服务。
     */
    public RelatedRecommendController(RelatedRecommendService relatedService) {
        this.relatedService = relatedService;
    }

    /**
     * 查询相似短剧。
     *
     * @param dramaId 当前短剧 ID
     * @param limit   返回条数，默认 6
     */
    @GetMapping("/related")
    public Mono<Result<RelatedFeed>> related(
            @RequestParam("dramaId") Long dramaId,
            @RequestParam(value = "limit", defaultValue = "6") int limit) {
        // 1. 向量检索和 Feign 都是阻塞调用，切到弹性线程池
        return Mono.fromCallable(() -> relatedService.feed(dramaId, limit))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(REQUEST_TIMEOUT)
                .map(Result::ok)
                // 2. 相关推荐失败不能影响详情页，返回空降级结果
                .onErrorResume(Exception.class,
                        e -> Mono.just(Result.ok(RelatedFeed.empty(true))));
    }
}
