package com.msd.content.controller;

import com.msd.common.result.Result;
import com.msd.content.dto.CommentSummaryRequest;
import com.msd.content.dto.CommentSummaryResponse;
import com.msd.content.service.CommentSummaryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 评论课代表内部接口，只允许 interaction 服务直连。
 */
@RestController
@RequestMapping("/internal/ai")
@ConditionalOnProperty(prefix = "msd.ai.comment-summary", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CommentSummaryController {

    // 评论摘要服务
    private final CommentSummaryService summaryService;

    /**
     * 注入评论摘要服务。
     */
    public CommentSummaryController(CommentSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /**
     * 生成评论摘要；失败返回降级空结果。
     */
    @PostMapping("/comment-summary")
    public Mono<Result<CommentSummaryResponse>> summarize(
            @RequestBody CommentSummaryRequest request) {
        // 1. 模型调用是阻塞的，切到弹性线程池
        return Mono.fromCallable(() -> summaryService.summarize(request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }
}
