package com.msd.agent.controller;

import com.msd.agent.dto.RemoteAuditReply;
import com.msd.agent.dto.RemoteAuditRequest;
import com.msd.agent.routing.PromptGuard;
import com.msd.agent.service.RemoteAuditService;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 远程 audit-agent 演示接口。
 *
 * <p>直接触发 A2A 调用，便于确定性验证 Nacos 发现、重试和降级；
 * Supervisor 的工具调用链使用同一套 {@link RemoteAuditService}。</p>
 */
@RestController
@RequestMapping("/internal/ai/agent/audit")
@Profile("!audit")
public class RemoteAuditController {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(RemoteAuditController.class);

    /** 单次审核内容最大长度。 */
    private static final int MAX_CONTENT_LENGTH = 5000;

    /** 会话 ID 只允许字母、数字、下划线和短横线，避免污染日志。 */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    // 远程审核服务（A2A 调用、重试与降级）
    private final RemoteAuditService remoteAuditService;
    // 待审核文本 Prompt 防护
    private final PromptGuard promptGuard;

    /**
     * 注入远程审核服务和 Prompt 防护。
     */
    public RemoteAuditController(RemoteAuditService remoteAuditService,
            PromptGuard promptGuard) {
        this.remoteAuditService = remoteAuditService;
        this.promptGuard = promptGuard;
    }

    /**
     * 发起一次远程审核；远程不可用时返回 degraded=true 的降级结果。
     */
    @PostMapping("/remote")
    public Mono<Result<RemoteAuditReply>> auditRemote(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId,
            @RequestBody(required = false) RemoteAuditRequest request,
            ServerHttpResponse response) {
        // 1. 校验待审核内容
        if (request == null || !StringUtils.hasText(request.content())) {
            return Mono.just(Result.fail(
                    ResultCode.PARAM_ERROR.getCode(), "content 不能为空"));
        }
        String content = request.content().trim();
        if (content.length() > MAX_CONTENT_LENGTH) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    "content 不能超过 " + MAX_CONTENT_LENGTH + " 字"));
        }

        // 2. 审核内容同样不可信，清洗内部标记并拦截指令注入
        PromptGuard.CheckResult guardResult = promptGuard.inspect(content);
        if (guardResult.rejected()) {
            log.warn("Audit prompt rejected: userId={}, markers={}",
                    userId, guardResult.removedMarkers());
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    guardResult.reason()));
        }
        String safeContent = guardResult.text();
        if (!StringUtils.hasText(safeContent)) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    "content 清洗后不能为空"));
        }

        // 3. 会话 ID 为空则生成；直连接口不依赖网关注入的 userId
        String sessionId = StringUtils.hasText(request.sessionId())
                ? request.sessionId().trim()
                : UUID.randomUUID().toString();
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    "sessionId 只允许字母、数字、下划线和短横线，最长 64 位"));
        }
        String resolvedUserId = StringUtils.hasText(userId) ? userId : "system";

        // 4. RemoteAuditService 内部已做重试和降级，这里只做统一包装
        return remoteAuditService.audit(safeContent, resolvedUserId, sessionId)
                .map(Result::ok)
                .onErrorResume(ex -> {
                    log.error("Remote audit endpoint failed: sessionId={}", sessionId, ex);
                    return Mono.just(Result.fail(ResultCode.SYSTEM_ERROR.getCode(),
                            "远程审核调用失败"));
                });
    }
}
