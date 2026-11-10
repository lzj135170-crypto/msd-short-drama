package com.msd.content.audit;

import com.msd.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 结构化审核内部接口，只允许服务间直连。
 */
@RestController
@RequestMapping("/internal/ai/audit")
@ConditionalOnProperty(prefix = "msd.ai.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionAuditController {

    // 结构化审核服务
    private final StructuredAuditService auditService;

    /**
     * 注入结构化审核服务。
     */
    public CompanionAuditController(StructuredAuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 审核一段文本；降级时 verdict=null，调用方不得自动通过。
     */
    @PostMapping("/structured")
    public Mono<Result<AuditCheckResponse>> structured(
            @Valid @RequestBody AuditCheckRequest request) {
        // 1. 审核包含模型调用，切到弹性线程池执行
        return Mono.fromCallable(() -> Result.ok(auditService.audit(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
