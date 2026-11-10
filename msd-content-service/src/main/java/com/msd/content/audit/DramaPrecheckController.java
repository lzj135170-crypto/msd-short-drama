package com.msd.content.audit;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 创作者 AI 预检接口。
 *
 * <p>经网关访问 /api/content/internal/ai/audit/precheck；只允许创作者和管理员，
 * 用户 ID 由 JWT 过滤器注入。预检不写任何审核状态。</p>
 */
@RestController
@RequestMapping("/internal/ai/audit")
@ConditionalOnProperty(prefix = "msd.ai.precheck", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DramaPrecheckController {

    /** 预检总超时，覆盖合规、文案建议和 Rex 的并行等待。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(35);

    // 预检服务
    private final DramaPrecheckService precheckService;

    /**
     * 注入预检服务。
     */
    public DramaPrecheckController(DramaPrecheckService precheckService) {
        this.precheckService = precheckService;
    }

    /**
     * 对创作者当前输入执行合规和文案预检。
     */
    @PostMapping("/precheck")
    public Mono<Result<DramaPrecheckResponse>> precheck(
            @Valid @RequestBody DramaPrecheckRequest request,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false)
            String userIdHeader,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false)
            String role) {
        // 1. 只允许登录的创作者或管理员
        Long userId = RoleGuard.requireUserId(userIdHeader);
        RoleGuard.requireCreatorOrAdmin(role);
        // 2. 模型和配额都是阻塞调用，切到弹性线程池，避免阻塞 Netty
        return Mono.fromCallable(() -> Result.ok(precheckService.precheck(userId, request)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(REQUEST_TIMEOUT);
    }
}
