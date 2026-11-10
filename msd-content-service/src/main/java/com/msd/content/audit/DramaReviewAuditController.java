package com.msd.content.audit;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 短剧 AI 审核运维接口。
 *
 * <p>只允许直连 8107，网关不路由；批量审核可能耗时，调用方需要设置较长超时。</p>
 */
@RestController
@RequestMapping("/internal/ai/audit/dramas")
@ConditionalOnProperty(prefix = "msd.ai.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DramaReviewAuditController {

    // 短剧审核编排服务
    private final DramaReviewAuditService auditService;

    /**
     * 注入短剧审核编排服务。
     */
    public DramaReviewAuditController(DramaReviewAuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 批量审核待审短剧。
     */
    @PostMapping("/pending")
    public Mono<Result<DramaReviewAuditBatchResult>> pending(
            @RequestParam(value = "limit", defaultValue = "5") Integer limit,
            @RequestParam(value = "includeFailed", defaultValue = "false") boolean includeFailed,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 1. 经网关调用时必须是管理员；直连 8107 的运维调用可以不带角色
        RoleGuard.requireAdminIfPresent(role);
        // 2. Feign 和模型调用都在 boundedElastic 执行
        return Mono.fromCallable(() -> Result.ok(
                        auditService.auditPending(limit, includeFailed)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 审核单部短剧。
     */
    @PostMapping("/{dramaId}")
    public Mono<Result<DramaReviewAuditItem>> one(
            @PathVariable("dramaId") Long dramaId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 1. 经网关调用时必须是管理员
        RoleGuard.requireAdminIfPresent(role);
        // 2. 非待审状态返回 data=null
        return Mono.fromCallable(() -> Result.ok(auditService.auditOne(dramaId)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
