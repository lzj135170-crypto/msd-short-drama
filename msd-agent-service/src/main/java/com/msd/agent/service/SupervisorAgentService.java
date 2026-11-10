package com.msd.agent.service;

import com.alibaba.csp.sentinel.AsyncEntry;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.msd.agent.dto.AgentChatReply;
import com.msd.agent.routing.AgentCardExtractor;
import com.msd.agent.routing.AgentReplySanitizer;
import com.msd.agent.routing.IntentRoute;
import com.msd.agent.routing.IntentRouter;
import com.msd.agent.routing.RouteProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Locale;

/**
 * Supervisor 调用服务：规则路由前置，必要时走 AUDIT 快速通道或 AgentScope。
 *
 * <p>audit profile 只提供 A2A 审核服务，不创建 Supervisor 调用链。</p>
 */
@Service
@Profile("!audit")
public class SupervisorAgentService {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(SupervisorAgentService.class);

    // 懒加载的 Supervisor Agent，避免模型或 Redis 未就绪时阻塞启动
    private final ObjectProvider<HarnessAgent> agentProvider;
    // 单次对话超时时间
    private final Duration requestTimeout;
    // 规则前置路由器
    private final IntentRouter intentRouter;
    // 路由阈值和快速通道开关
    private final RouteProperties routeProperties;
    // 远程 audit-agent 快速通道
    private final RemoteAuditService remoteAuditService;
    // 回复内部标记清洗
    private final AgentReplySanitizer replySanitizer;
    // Sentinel agentChat 入口
    private final AgentChatSentinel sentinel;
    // 路由/对话指标
    private final MeterRegistry meterRegistry;

    /**
     * 注入 Agent、路由、快速通道和指标。
     */
    public SupervisorAgentService(ObjectProvider<HarnessAgent> agentProvider,
            @Value("${msd.ai.agent.request-timeout:120s}") Duration requestTimeout,
            IntentRouter intentRouter,
            RouteProperties routeProperties,
            RemoteAuditService remoteAuditService,
            AgentReplySanitizer replySanitizer,
            AgentChatSentinel sentinel,
            MeterRegistry meterRegistry) {
        this.agentProvider = agentProvider;
        this.requestTimeout = requestTimeout;
        this.intentRouter = intentRouter;
        this.routeProperties = routeProperties;
        this.remoteAuditService = remoteAuditService;
        this.replySanitizer = replySanitizer;
        this.sentinel = sentinel;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 使用 userId + sessionId 调用 Supervisor；同一会话的状态由 Redis 恢复。
     */
    public Mono<AgentChatReply> chat(String userId, String sessionId,
                                     String message, Long episodeId) {
        return Mono.defer(() -> {
            // 1. 规则前置路由，决策不调用模型
            IntentRouter.RouteDecision decision = intentRouter.route(message, userId, sessionId);
            log.info("Agent route: userId={}, sessionId={}, route={}, source={}, "
                            + "confidence={}, latencyMs={}",
                    userId, sessionId, decision.route(), decision.source(),
                    decision.confidence(), decision.latencyMs());

            // 2. Sentinel 覆盖整条异步链路，被限流/熔断时快速失败
            AsyncEntry entry;
            try {
                entry = sentinel.enter();
            } catch (BlockException ex) {
                log.warn("Agent chat blocked by Sentinel: userId={}, sessionId={}",
                        userId, sessionId);
                return Mono.error(new AgentUnavailableException(
                        "当前对话请求过多，请稍后重试"));
            }

            // 3. AUDIT 高置信度走远程无状态快速通道；失败自动回退 Supervisor
            Mono<AgentChatReply> result;
            if (fastPathEligible(decision)) {
                result = auditFastPath(userId, sessionId, message, decision);
            } else {
                result = supervisorCall(userId, sessionId, message, episodeId, decision);
            }
            return result
                    .doOnSuccess(reply -> meterRegistry.counter("agent.chat",
                                    "route", decision.route().name(),
                                    "path", fastPathEligible(decision) ? "audit-fast" : "supervisor",
                                    "outcome", "success")
                            .increment())
                    .doOnError(ex -> meterRegistry.counter("agent.chat",
                                    "route", decision.route().name(),
                                    "path", fastPathEligible(decision) ? "audit-fast" : "supervisor",
                                    "outcome", "error")
                            .increment())
                    .doFinally(signal -> entry.exit());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * AUDIT 高置信度且开关打开时允许快速通道。
     */
    private boolean fastPathEligible(IntentRouter.RouteDecision decision) {
        return routeProperties.isFastPathAudit()
                && decision.route() == IntentRoute.AUDIT
                && decision.confidence() >= routeProperties.getHighConfidence();
    }

    /**
     * 调用远程 audit-agent；降级或异常时回退 Supervisor。
     */
    private Mono<AgentChatReply> auditFastPath(String userId, String sessionId,
            String message, IntentRouter.RouteDecision decision) {
        long startNanos = System.nanoTime();
        return remoteAuditService.audit(message, userId, sessionId)
                .timeout(requestTimeout)
                .flatMap(reply -> {
                    if (reply.degraded()) {
                        log.warn("Audit fast path degraded, fallback to supervisor: "
                                + "userId={}, sessionId={}", userId, sessionId);
                        return supervisorCall(userId, sessionId, message, null, decision);
                    }
                    return Mono.just(toReply(reply.reply(), sessionId, decision));
                })
                .onErrorResume(ex -> {
                    log.warn("Audit fast path failed, fallback to supervisor: userId={}, "
                            + "sessionId={}", userId, sessionId, ex);
                    return supervisorCall(userId, sessionId, message, null, decision);
                })
                .doOnSuccess(reply -> meterRegistry.timer("agent.chat.fastpath")
                        .record(Duration.ofNanos(System.nanoTime() - startNanos)));
    }

    /**
     * 调用 AgentScope Supervisor，并在高置信度时附加服务端路由提示。
     */
    private Mono<AgentChatReply> supervisorCall(String userId, String sessionId,
            String message, Long episodeId, IntentRouter.RouteDecision decision) {
        // 1. 获取 Agent，未启用或初始化失败时抛出业务异常
        HarnessAgent agent = resolveAgent();
        // 2. 组装运行时上下文，AgentScope 据此恢复会话状态
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();

        // 3. 高置信度时把服务端路由提示放到消息最前面
        StringBuilder agentMessage = new StringBuilder();
        if (decision.highConfidence(routeProperties.getHighConfidence())) {
            agentMessage.append("[ROUTE_HINT=")
                    .append(decision.route().name().toLowerCase(Locale.ROOT))
                    .append(";CONF=")
                    .append(String.format(Locale.ROOT, "%.2f", decision.confidence()))
                    .append("]\n");
        }
        // 4. 伴看场景把当前剧集 ID 放到用户消息前缀，供 companion-agent 直接使用
        if (episodeId != null && episodeId > 0) {
            agentMessage.append("[当前剧集ID=").append(episodeId).append("]\n");
        }
        agentMessage.append(message);

        log.info("Agent chat start: userId={}, sessionId={}, episodeId={}, route={}",
                userId, sessionId, episodeId, decision.route());
        // 5. 调用并统一处理超时、空响应和日志
        return agent.call(new UserMessage(agentMessage.toString()), context)
                .timeout(requestTimeout)
                .switchIfEmpty(Mono.error(new AgentUnavailableException("Agent 未返回结果")))
                .map(reply -> toReply(reply.getTextContent(), sessionId, decision))
                .doOnSuccess(reply -> log.info(
                        "Agent chat done: userId={}, sessionId={}", userId, sessionId))
                .doOnError(ex -> log.error(
                        "Agent chat failed: userId={}, sessionId={}", userId, sessionId, ex));
    }

    /**
     * 构造响应：先提取卡片，再清洗内部标记。
     */
    private AgentChatReply toReply(String reply, String sessionId,
            IntentRouter.RouteDecision decision) {
        AgentCardExtractor.Extraction extraction = AgentCardExtractor.extract(reply);
        return new AgentChatReply(
                replySanitizer.sanitize(extraction.text()),
                sessionId,
                decision.route().name(),
                decision.source(),
                decision.confidence(),
                Math.round(decision.latencyMs()),
                extraction.cards());
    }

    /**
     * 获取 Agent；懒加载失败时转成可展示的业务异常，保留原始异常到日志。
     */
    private HarnessAgent resolveAgent() {
        try {
            HarnessAgent agent = agentProvider.getIfAvailable();
            if (agent == null) {
                throw new AgentUnavailableException(
                        "AgentScope 未启用：缺少 DEEPSEEK_API_KEY");
            }
            return agent;
        } catch (AgentUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("AgentScope initialization failed", ex);
            throw new AgentUnavailableException(
                    "Agent 初始化失败，请检查模型、Redis 和工作区配置", ex);
        }
    }
}
