package com.msd.agent.controller;

import com.msd.agent.dto.AgentChatReply;
import com.msd.agent.dto.AgentChatRequest;
import com.msd.agent.routing.PromptGuard;
import com.msd.agent.service.AgentUnavailableException;
import com.msd.agent.service.SupervisorAgentService;
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
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * AgentScope Supervisor 对话接口。
 *
 * <p>外部通过网关访问：POST /api/agent/internal/ai/agent/chat；
 * userId 由网关 JwtAuthenticationFilter 注入，客户端只传 message/sessionId。</p>
 */
@RestController
@RequestMapping("/internal/ai/agent")
@Profile("!audit")
public class AgentChatController {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    /** AgentScope 未启用或初始化失败。 */
    private static final int AGENT_UNAVAILABLE_CODE = 50300;

    /** 单条消息最大长度。 */
    private static final int MAX_MESSAGE_LENGTH = 2000;

    /** 会话 ID 只允许字母、数字、下划线和短横线，避免污染 Redis key。 */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    // Supervisor 对话服务
    private final SupervisorAgentService supervisorAgentService;
    // 用户文本 Prompt 防护
    private final PromptGuard promptGuard;

    /**
     * 注入 Supervisor 调用服务和 Prompt 防护。
     */
    public AgentChatController(SupervisorAgentService supervisorAgentService,
            PromptGuard promptGuard) {
        this.supervisorAgentService = supervisorAgentService;
        this.promptGuard = promptGuard;
    }

    /**
     * 发起一次 Supervisor 对话；sessionId 为空时生成新的 UUID。
     */
    @PostMapping("/chat")
    public Mono<Result<AgentChatReply>> chat(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId,
            @RequestBody(required = false) AgentChatRequest request,
            ServerHttpResponse response) {
        // 1. userId 由网关注入，直连服务时也必须显式传
        if (!StringUtils.hasText(userId)) {
            return Mono.just(Result.fail(ResultCode.UNAUTHORIZED));
        }

        // 2. 校验消息长度，避免把超长内容直接送入模型
        if (request == null || !StringUtils.hasText(request.message())) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(), "message 不能为空"));
        }
        String message = request.message().trim();
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    "message 不能超过 " + MAX_MESSAGE_LENGTH + " 字"));
        }

        // 3. 清洗用户伪造的内部标记，拦截指令注入
        PromptGuard.CheckResult guardResult = promptGuard.inspect(message);
        if (guardResult.rejected()) {
            log.warn("Agent prompt rejected: userId={}, markers={}",
                    userId, guardResult.removedMarkers());
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    guardResult.reason()));
        }
        String safeMessage = guardResult.text();
        if (!StringUtils.hasText(safeMessage)) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    "message 清洗后不能为空"));
        }

        // 4. 会话 ID 为空则生成；非空时校验字符集
        String sessionId = StringUtils.hasText(request.sessionId())
                ? request.sessionId().trim()
                : UUID.randomUUID().toString();
        if (!SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    "sessionId 只允许字母、数字、下划线和短横线，最长 64 位"));
        }

        // 5. 伴看场景可选传当前剧集 ID，必须是正整数
        Long episodeId = request.episodeId();
        if (episodeId != null && episodeId <= 0) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    "episodeId 必须为正整数"));
        }

        // 6. RuntimeContext 传入 userId + sessionId，由 AgentScope 定位会话状态
        // 7. 统一错误映射：未配置/初始化失败 50300，超时和模型异常 50000
        return Mono.defer(() -> supervisorAgentService.chat(
                        userId, sessionId, safeMessage, episodeId))
                .map(Result::ok)
                .onErrorResume(AgentUnavailableException.class, ex -> {
                    log.warn("Agent unavailable: {}", ex.getMessage());
                    return Mono.just(Result.fail(AGENT_UNAVAILABLE_CODE, ex.getMessage()));
                })
                .onErrorResume(TimeoutException.class, ex -> {
                    log.warn("Agent timeout: userId={}, sessionId={}", userId, sessionId);
                    return Mono.just(Result.fail(ResultCode.SYSTEM_ERROR.getCode(),
                            "Agent 响应超时，请稍后重试"));
                })
                .onErrorResume(ex -> {
                    log.error("Agent chat error: userId={}, sessionId={}", userId, sessionId, ex);
                    return Mono.just(Result.fail(ResultCode.SYSTEM_ERROR.getCode(),
                            "Agent 调用失败，请稍后重试"));
                });
    }
}
