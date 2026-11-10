package com.msd.drama.demo;

import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.drama.cache.CacheProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 虚拟线程与传统平台线程池的 AI 并发对比 demo。
 *
 * <p>只用于 dev 环境，接口不经过网关。</p>
 */
@Service
@Profile("dev")
@ConditionalOnProperty(prefix = "msd.cache.demo", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AiFanoutDemoService {

    // 虚拟线程模式标识
    private static final String MODE_VIRTUAL = "virtual";
    // 固定平台线程池模式标识
    private static final String MODE_PLATFORM = "platform";
    // 平台线程池大小上限，防止误压垮下游
    private static final int MAX_POOL = 200;

    // 下游 AI 客户端
    private final AiChatClient aiChatClient;
    // demo 配置，提供最大调用数
    private final CacheProperties properties;

    /**
     * 注入下游 AI 客户端和 demo 配置。
     */
    public AiFanoutDemoService(AiChatClient aiChatClient, CacheProperties properties) {
        this.aiChatClient = aiChatClient;
        this.properties = properties;
    }

    /**
     * 单次调用，返回当前请求线程是否为虚拟线程。
     */
    public AiSingleResult single(String prompt) {
        // 1. 记录请求线程信息，验证全局虚拟线程是否生效
        Thread thread = Thread.currentThread();
        long start = System.nanoTime();

        // 2. 同步等待下游；JDK HttpClient 在 socket I/O 时卸载虚拟线程
        AgentReply reply = aiChatClient.chat(prompt);

        return new AiSingleResult(
                thread.getName(),
                thread.isVirtual(),
                elapsedMillis(start),
                reply.reply(),
                reply.degraded()
        );
    }

    /**
     * 并发扇出；virtual 模式一请求一虚拟线程，platform 模式使用固定线程池。
     */
    public AiFanoutResult fanout(int calls, String mode, int pool, String prompt) {
        // 1. 校验模式，并限制调用数和线程池大小，防止误压垮下游
        String normalizedMode = normalizeMode(mode);
        int total = Math.max(1, Math.min(calls, properties.getDemo().getMaxCalls()));
        int poolSize = Math.max(1, Math.min(pool, MAX_POOL));
        ExecutorService executor = MODE_PLATFORM.equals(normalizedMode)
                ? Executors.newFixedThreadPool(poolSize)
                : Executors.newVirtualThreadPerTaskExecutor();

        // 2. 提交全部任务并等待结束；单个任务异常在任务内部转换为失败结果
        long start = System.nanoTime();
        List<CallOutcome> outcomes = new ArrayList<>(total);
        try {
            List<Callable<CallOutcome>> tasks = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                tasks.add(() -> invoke(prompt));
            }
            for (Future<CallOutcome> future : executor.invokeAll(tasks)) {
                outcomes.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.SYSTEM_ERROR, "并发调用被中断");
        } catch (ExecutionException e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "并发调用失败: " + e.getCause());
        } finally {
            executor.shutdown();
        }
        long elapsedMs = elapsedMillis(start);

        // 3. 汇总成功、失败、降级和延迟分位；分位统计包含失败调用
        return summarize(total, normalizedMode, poolSize, elapsedMs, outcomes);
    }

    /**
     * 执行单次下游调用；异常转换为失败结果，避免中断整批扇出。
     */
    private CallOutcome invoke(String prompt) {
        long start = System.nanoTime();
        try {
            AgentReply reply = aiChatClient.chat(prompt);
            return new CallOutcome(true, reply.degraded(), elapsedMillis(start));
        } catch (RuntimeException e) {
            // 单次失败只标记本次结果，由汇总层统计
            return new CallOutcome(false, false, elapsedMillis(start));
        }
    }

    /**
     * 统计成功、失败、降级数量以及延迟分位。
     */
    private AiFanoutResult summarize(int total, String mode, int pool,
                                     long elapsedMs, List<CallOutcome> outcomes) {
        // 1. 逐个统计结果，同时收集延迟
        int success = 0;
        int degraded = 0;
        long[] latencies = new long[outcomes.size()];
        for (int i = 0; i < outcomes.size(); i++) {
            CallOutcome outcome = outcomes.get(i);
            if (outcome.success()) {
                success++;
            }
            if (outcome.degraded()) {
                degraded++;
            }
            latencies[i] = outcome.elapsedMs();
        }

        // 2. 排序后取 p50、p95、max
        Arrays.sort(latencies);
        Thread requestThread = Thread.currentThread();
        return new AiFanoutResult(
                total,
                mode,
                pool,
                success,
                total - success,
                degraded,
                elapsedMs,
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                latencies.length == 0 ? 0 : latencies[latencies.length - 1],
                requestThread.isVirtual(),
                requestThread.getName()
        );
    }

    /**
     * 最近秩法分位；空数组返回 0。
     */
    static long percentile(long[] sorted, double ratio) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(ratio * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    /**
     * 归一化模式参数；缺省为 virtual，非法值抛参数异常。
     */
    private static String normalizeMode(String mode) {
        // 1. 空值或 virtual 统一按虚拟线程模式处理
        if (mode == null || mode.isBlank() || MODE_VIRTUAL.equalsIgnoreCase(mode)) {
            return MODE_VIRTUAL;
        }
        // 2. platform 模式原样归一化
        if (MODE_PLATFORM.equalsIgnoreCase(mode)) {
            return MODE_PLATFORM;
        }
        // 3. 其他值直接拒绝，避免拼写错误静默生效
        throw new BizException(ResultCode.PARAM_ERROR, "mode 只支持 virtual 或 platform");
    }

    /**
     * 纳秒计时差转换为毫秒。
     */
    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 单次调用结果；失败不抛出，由汇总层统计。
     *
     * @param success   是否调用成功
     * @param degraded  下游是否降级返回
     * @param elapsedMs 调用耗时，单位毫秒
     */
    private record CallOutcome(boolean success, boolean degraded, long elapsedMs) {
    }
}
