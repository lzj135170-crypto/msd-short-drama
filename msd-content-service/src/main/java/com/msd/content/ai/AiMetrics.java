package com.msd.content.ai;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * AI 链路基础指标。
 *
 * <p>只记录场景、状态、降级标记和耗时，不记录用户原文。</p>
 */
@Component
public class AiMetrics {

    // Micrometer 注册表；测试可传 null 关闭指标
    private final MeterRegistry registry;

    /**
     * 注入 Micrometer 注册表。
     */
    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次 AI 请求的状态、耗时和降级标记。
     */
    public void recordRequest(String scene, boolean degraded, Collection<String> flags,
                              long elapsedMs) {
        if (registry == null) {
            return;
        }
        // 1. 请求总量按场景和状态拆分
        registry.counter("ai_request_total", "scene", scene,
                "status", degraded ? "degraded" : "success").increment();
        // 2. Micrometer 会为 timer 追加 _seconds，最终指标为 ai_latency_seconds
        registry.timer("ai_latency", "scene", scene)
                .record(Math.max(elapsedMs, 0), TimeUnit.MILLISECONDS);
        // 3. 每个降级标记单独计数
        if (flags != null) {
            flags.forEach(flag -> registry.counter("ai_degraded_total",
                    "scene", scene, "flag", flag).increment());
        }
    }

    /**
     * 记录流式回答首个增量 token 的等待时间。
     */
    public void recordFirstToken(String scene, long elapsedMs) {
        if (registry == null) {
            return;
        }
        // Micrometer 会追加 _seconds，最终指标为 ai_first_token_seconds
        registry.timer("ai_first_token", "scene", scene)
                .record(Math.max(elapsedMs, 0), TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一次无结果。
     */
    public void recordNoResult() {
        if (registry != null) {
            registry.counter("ai_rag_no_result_total").increment();
        }
    }

    /**
     * 记录一次缓存命中，type 为 exact 或 semantic。
     */
    public void recordCacheHit(String type) {
        if (registry != null) {
            registry.counter("ai_cache_hit_total", "type", type).increment();
        }
    }

    /**
     * 记录估算 token 用量，variant 为空时归入 default。
     */
    public void recordTokens(String scene, String variant, int tokens) {
        if (registry == null || tokens <= 0) {
            return;
        }
        registry.counter("ai_tokens_total", "scene", scene,
                "variant", variant == null || variant.isBlank() ? "default" : variant)
                .increment(tokens);
    }

    /**
     * 记录一次实验分桶，便于按变体观察请求量和效果。
     */
    public void recordExperiment(String experiment, String variant) {
        if (registry == null || experiment == null || experiment.isBlank()) {
            return;
        }
        registry.counter("ai_experiment_total", "experiment", experiment,
                "variant", variant == null || variant.isBlank() ? "inactive" : variant)
                .increment();
    }
}
