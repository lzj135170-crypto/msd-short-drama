package com.msd.agent.controller;

import com.msd.agent.dto.AgentRouteRequest;
import com.msd.agent.routing.IntentRouter;
import com.msd.agent.routing.PromptGuard;
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
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;

/**
 * 意图路由调试接口，只做规则/缓存决策，不调用大模型。
 */
@RestController
@RequestMapping("/internal/ai/agent")
@Profile("!audit")
public class AgentRouteController {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AgentRouteController.class);
    /** 单条消息最大长度。 */
    private static final int MAX_MESSAGE_LENGTH = 2000;
    /** 路由调试超时，避免 Redis 抖动拖住请求。 */
    private static final Duration ROUTE_TIMEOUT = Duration.ofSeconds(2);

    // 规则路由器
    private final IntentRouter intentRouter;
    // Prompt 防护
    private final PromptGuard promptGuard;

    /**
     * 注入路由器和 Prompt 防护。
     */
    public AgentRouteController(IntentRouter intentRouter, PromptGuard promptGuard) {
        this.intentRouter = intentRouter;
        this.promptGuard = promptGuard;
    }

    /**
     * 返回规则路由结果；不触发 AgentScope。
     */
    @PostMapping("/route")
    public Mono<Result<IntentRouter.RouteDecision>> route(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId,
            @RequestBody(required = false) AgentRouteRequest request,
            ServerHttpResponse response) {
        // 1. 校验消息
        if (request == null || !StringUtils.hasText(request.message())) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(), "message 不能为空"));
        }
        String message = request.message().trim();
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    "message 不能超过 " + MAX_MESSAGE_LENGTH + " 字"));
        }

        // 2. 与聊天入口使用同一套 Prompt 清洗
        PromptGuard.CheckResult check = promptGuard.inspect(message);
        if (check.rejected()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(), check.reason()));
        }
        if (!StringUtils.hasText(check.text())) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR.getCode(),
                    "message 清洗后不能为空"));
        }

        // 3. 调试请求允许没有 userId，用独立会话避免污染真实粘性缓存
        String resolvedUser = StringUtils.hasText(userId) ? userId.trim() : "debug";
        String sessionId = StringUtils.hasText(request.sessionId())
                ? request.sessionId().trim()
                : "route-" + UUID.randomUUID();

        // 4. Redis 读取放弹性线程池，整体 2 秒超时
        return Mono.fromCallable(() -> intentRouter.route(check.text(), resolvedUser, sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(ROUTE_TIMEOUT)
                .map(Result::ok)
                .onErrorResume(ex -> {
                    log.warn("Agent route debug failed: userId={}, sessionId={}",
                            resolvedUser, sessionId, ex);
                    return Mono.just(Result.fail(ResultCode.SYSTEM_ERROR.getCode(),
                            "路由决策失败"));
                });
    }
}
