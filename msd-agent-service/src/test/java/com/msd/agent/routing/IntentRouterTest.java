package com.msd.agent.routing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则前置意图路由测试。
 */
class IntentRouterTest {

    /**
     * 命中审计关键词时高置信度交给 audit-agent。
     */
    @Test
    void routesAuditByRule() {
        IntentRouter router = router(new RouteCacheStub());

        IntentRouter.RouteDecision decision = router.route(
                "请审核这段评论是否违规", "1003", "session-1");

        assertThat(decision.route()).isEqualTo(IntentRoute.AUDIT);
        assertThat(decision.agent()).isEqualTo("audit-agent");
        assertThat(decision.source()).isEqualTo("RULE");
        assertThat(decision.confidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(decision.matchedRule()).isEqualTo("audit-keyword");
    }

    /**
     * 关注某一集的问题命中伴看规则。
     */
    @Test
    void routesCompanionByEpisodePattern() {
        IntentRouter router = router(new RouteCacheStub());

        IntentRouter.RouteDecision decision = router.route(
                "第3集里他们为什么吵架", "1003", "session-2");

        assertThat(decision.route()).isEqualTo(IntentRoute.COMPANION);
        assertThat(decision.agent()).isEqualTo("companion-agent");
        assertThat(decision.source()).isEqualTo("RULE");
    }

    /**
     * 规则无命中时不猜领域，交给 Supervisor 模型兜底。
     */
    @Test
    void fallsBackToSupervisorForUnknownIntent() {
        IntentRouter router = router(new RouteCacheStub());

        IntentRouter.RouteDecision decision = router.route(
                "你好呀", "1003", "session-3");

        assertThat(decision.route()).isEqualTo(IntentRoute.SUPERVISOR);
        assertThat(decision.agent()).isEqualTo("supervisor");
        assertThat(decision.source()).isEqualTo("MODEL");
    }

    /**
     * 完全相同的消息命中缓存，不再重复匹配规则。
     */
    @Test
    void usesExactCache() {
        RouteCacheStub cache = new RouteCacheStub();
        cache.exact = new RouteCache.CachedRoute(
                IntentRoute.DATA, "data-agent", 0.9, "data-keyword");
        IntentRouter router = router(cache);

        IntentRouter.RouteDecision decision = router.route(
                "看一下播放量", "1003", "session-4");

        assertThat(decision.route()).isEqualTo(IntentRoute.DATA);
        assertThat(decision.source()).isEqualTo("CACHE");
        assertThat(cache.exactReads).isEqualTo(1);
    }

    /**
     * 无规则命中时用会话粘性保持上下文，但置信度不足以走快速通道。
     */
    @Test
    void usesStickyRouteWithLowerConfidence() {
        RouteCacheStub cache = new RouteCacheStub();
        cache.sticky = new RouteCache.CachedRoute(
                IntentRoute.COMPANION, "companion-agent", 0.9, "companion-keyword");
        IntentRouter router = router(cache);

        IntentRouter.RouteDecision decision = router.route(
                "那这个呢", "1003", "session-5");

        assertThat(decision.route()).isEqualTo(IntentRoute.COMPANION);
        assertThat(decision.source()).isEqualTo("CACHE");
        assertThat(decision.confidence()).isLessThan(0.7);
    }

    /**
     * 关闭路由后返回 Supervisor 兜底，不影响旧链路。
     */
    @Test
    void disabledRoutingFallsBack() {
        RouteProperties properties = properties();
        properties.setEnabled(false);
        IntentRouter router = new IntentRouter(
                properties, new RouteCacheStub(), new SimpleMeterRegistry());

        IntentRouter.RouteDecision decision = router.route("审核一下", "1003", "session-6");

        assertThat(decision.route()).isEqualTo(IntentRoute.SUPERVISOR);
        assertThat(decision.source()).isEqualTo("FALLBACK");
    }

    /**
     * 构造覆盖四类意图的测试规则。
     */
    private IntentRouter router(RouteCacheStub cache) {
        return new IntentRouter(properties(), cache, new SimpleMeterRegistry());
    }

    /**
     * 规则权重足够高时单关键词即可高置信度。
     */
    private RouteProperties properties() {
        RouteProperties properties = new RouteProperties();
        properties.setEnabled(true);
        properties.setHighConfidence(0.7);
        properties.setLowConfidence(0.4);
        properties.setRules(List.of(
                rule("audit-keyword", IntentRoute.AUDIT, "audit-agent", 0.9,
                        List.of("审核"), List.of()),
                rule("companion-pattern", IntentRoute.COMPANION, "companion-agent", 0.85,
                        List.of("前情", "剧情", "人物关系"),
                        List.of("第\\s*\\d+\\s*集")),
                rule("data-keyword", IntentRoute.DATA, "data-agent", 0.8,
                        List.of("热度", "榜单", "播放量"), List.of()),
                rule("social-keyword", IntentRoute.SOCIAL, "social-agent", 0.8,
                        List.of("评论回复", "文案", "活动"), List.of())));
        return properties;
    }

    /**
     * 构造单条路由规则。
     */
    private RouteProperties.Rule rule(String name, IntentRoute route, String agent,
            double weight, List<String> keywords, List<String> patterns) {
        RouteProperties.Rule rule = new RouteProperties.Rule();
        rule.setName(name);
        rule.setRoute(route);
        rule.setAgent(agent);
        rule.setWeight(weight);
        rule.setKeywords(keywords);
        rule.setPatterns(patterns);
        return rule;
    }

    /**
     * 内存缓存桩，避免单测依赖 Redis。
     */
    private static final class RouteCacheStub extends RouteCache {

        private RouteCache.CachedRoute exact;
        private RouteCache.CachedRoute sticky;
        private int exactReads;

        private RouteCacheStub() {
            super(null, new RouteProperties());
        }

        @Override
        public CachedRoute get(String normalizedMessage) {
            exactReads += 1;
            return exact;
        }

        @Override
        public CachedRoute getSticky(String userId, String sessionId) {
            return sticky;
        }

        @Override
        public void put(String normalizedMessage, CachedRoute route) {
        }

        @Override
        public void putSticky(String userId, String sessionId, CachedRoute route) {
        }
    }
}
