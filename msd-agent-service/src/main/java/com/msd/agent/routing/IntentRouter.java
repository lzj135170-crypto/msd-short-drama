package com.msd.agent.routing;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 规则前置的意图路由器。
 *
 * <p>高置信度规则给出路由提示；无命中时返回 SUPERVISOR，由模型兜底。
 * 这里只做本地规则和 Redis 命中，不调用大模型，因此决策延迟为毫秒级。</p>
 */
@Component
public class IntentRouter {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    // 路由配置
    private final RouteProperties properties;
    // 路由缓存
    private final RouteCache cache;
    // 指标注册表
    private final MeterRegistry meterRegistry;

    /**
     * 注入配置、缓存和指标。
     */
    public IntentRouter(RouteProperties properties, RouteCache cache, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.cache = cache;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 路由一条用户消息；不调用模型，返回规则或会话粘性结果。
     */
    public RouteDecision route(String message, String userId, String sessionId) {
        long startNanos = System.nanoTime();
        // 1. 开关关闭时保持旧链路
        if (!properties.isEnabled()) {
            return finish(new RouteDecision(IntentRoute.SUPERVISOR, IntentRoute.SUPERVISOR.agent(),
                    0.0, "FALLBACK", "", elapsedMs(startNanos)));
        }
        String normalized = normalize(message);
        if (!StringUtils.hasText(normalized)) {
            return finish(new RouteDecision(IntentRoute.SUPERVISOR, IntentRoute.SUPERVISOR.agent(),
                    0.0, "MODEL", "", elapsedMs(startNanos)));
        }

        // 2. 精确消息缓存优先，重复问题不再跑规则
        RouteCache.CachedRoute cached = cache.get(normalized);
        if (cached != null && cached.route() != null) {
            return finish(fromCache(cached, cached.confidence(), startNanos));
        }

        // 3. 逐条规则打分，取最高分；同分按优先级
        Match best = match(normalized);
        if (best != null && best.score() >= properties.getHighConfidence()
                && best.route() != IntentRoute.SUPERVISOR) {
            RouteCache.CachedRoute value = new RouteCache.CachedRoute(
                    best.route(), best.agent(), best.score(), best.ruleName());
            cache.put(normalized, value);
            cache.putSticky(userId, sessionId, value);
            return finish(new RouteDecision(best.route(), best.agent(), best.score(),
                    "RULE", best.ruleName(), elapsedMs(startNanos)));
        }

        // 4. 无高置信度规则时使用会话粘性；降低置信度，禁止触发 AUDIT 快速通道
        RouteCache.CachedRoute sticky = cache.getSticky(userId, sessionId);
        if (sticky != null && sticky.route() != null && sticky.route() != IntentRoute.SUPERVISOR) {
            double confidence = Math.min(sticky.confidence(),
                    Math.max(0.0, properties.getHighConfidence() - 0.01));
            return finish(fromCache(sticky, confidence, startNanos));
        }

        // 5. 规则候选不足或没有候选，交给 Supervisor 模型重新规划
        double confidence = best == null ? 0.0 : best.score();
        String matched = best == null ? "" : best.ruleName();
        return finish(new RouteDecision(IntentRoute.SUPERVISOR, IntentRoute.SUPERVISOR.agent(),
                confidence, "MODEL", matched, elapsedMs(startNanos)));
    }

    /**
     * 对全部规则打分并取最高分；同分时 priority 大的优先。
     */
    private Match match(String normalized) {
        Match best = null;
        for (RouteProperties.Rule rule : properties.getRules()) {
            if (rule == null || rule.getRoute() == null || rule.getRoute() == IntentRoute.SUPERVISOR) {
                continue;
            }
            int hits = 0;
            double score = 0.0;
            for (String keyword : rule.getKeywords()) {
                if (StringUtils.hasText(keyword)
                        && normalized.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                    hits += 1;
                    score = Math.max(score, rule.getWeight());
                }
            }
            for (String expression : rule.getPatterns()) {
                if (!StringUtils.hasText(expression)) {
                    continue;
                }
                try {
                    if (Pattern.compile(expression).matcher(normalized).find()) {
                        hits += 1;
                        score = Math.max(score, rule.getWeight());
                    }
                } catch (PatternSyntaxException ex) {
                    log.warn("invalid route pattern: rule={}, pattern={}",
                            rule.getName(), expression);
                }
            }
            if (hits == 0) {
                continue;
            }
            // 命中更多信号时小幅加分，但不超过 1.0
            score = Math.min(1.0, score + Math.max(0, hits - 1) * 0.05);
            String agent = StringUtils.hasText(rule.getAgent())
                    ? rule.getAgent()
                    : rule.getRoute().agent();
            Match candidate = new Match(rule.getRoute(), agent, score,
                    rule.getName(), rule.getPriority());
            if (best == null || candidate.betterThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 缓存结果统一标记为 CACHE。
     */
    private RouteDecision fromCache(RouteCache.CachedRoute cached, double confidence,
            long startNanos) {
        return new RouteDecision(cached.route(), cached.agent(), confidence,
                "CACHE", cached.matchedRule(), elapsedMs(startNanos));
    }

    /**
     * 记录指标并返回决策。
     */
    private RouteDecision finish(RouteDecision decision) {
        meterRegistry.counter("agent.route",
                "route", decision.route().name(),
                "source", decision.source(),
                "confidence", confidenceBucket(decision.confidence())).increment();
        meterRegistry.timer("agent.route.latency")
                .record(Duration.ofNanos((long) (decision.latencyMs() * 1_000_000L)));
        return decision;
    }

    /**
     * 置信度分桶，避免指标标签基数过大。
     */
    private String confidenceBucket(double confidence) {
        if (confidence >= properties.getHighConfidence()) {
            return "high";
        }
        if (confidence >= properties.getLowConfidence()) {
            return "medium";
        }
        return "low";
    }

    /**
     * 消息归一化：去首尾、合并空白、转小写。
     */
    private String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * 计算毫秒耗时。
     */
    private double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    /**
     * 规则命中结果。
     */
    private record Match(IntentRoute route, String agent, double score,
                         String ruleName, int priority) {

        /**
         * 分数更高，或同分时优先级更高。
         */
        private boolean betterThan(Match other) {
            if (Double.compare(score, other.score) != 0) {
                return score > other.score;
            }
            return priority > other.priority;
        }
    }

    /**
     * 路由决策，返回给调试接口和 Supervisor。
     */
    public record RouteDecision(
            IntentRoute route,
            String agent,
            double confidence,
            String source,
            String matchedRule,
            double latencyMs) {

        /**
         * 是否达到高置信度，供 Supervisor 决定是否加 ROUTE_HINT。
         */
        public boolean highConfidence(double threshold) {
            return route != IntentRoute.SUPERVISOR && confidence >= threshold;
        }
    }
}
