package com.msd.content.copilot;

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
 * 创作者 Copilot 接口。
 *
 * <p>经网关访问 /api/content/internal/ai/copilot/generate；只允许创作者和管理员，
 * 生成结果只读，不写审核状态或标签关系。</p>
 */
@RestController
@RequestMapping("/internal/ai/copilot")
@ConditionalOnProperty(prefix = "msd.ai.copilot", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CreatorCopilotController {

    /** 生成总超时，覆盖一次模型调用。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    // Copilot 服务
    private final CreatorCopilotService copilotService;

    /**
     * 注入 Copilot 服务。
     */
    public CreatorCopilotController(CreatorCopilotService copilotService) {
        this.copilotService = copilotService;
    }

    /**
     * 生成多个标题、副标题和标签方案。
     */
    @PostMapping("/generate")
    public Mono<Result<CreatorCopilotResponse>> generate(
            @Valid @RequestBody CreatorCopilotRequest request,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false)
            String userIdHeader,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false)
            String role) {
        // 1. 只允许登录的创作者或管理员
        Long userId = RoleGuard.requireUserId(userIdHeader);
        RoleGuard.requireCreatorOrAdmin(role);
        // 2. 模型调用是阻塞操作，切到弹性线程池，避免阻塞 Netty
        return Mono.fromCallable(() -> Result.ok(copilotService.generate(userId, request)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(REQUEST_TIMEOUT);
    }
}
