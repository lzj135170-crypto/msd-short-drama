package com.msd.content.audit;

import com.msd.common.result.Result;
import com.msd.content.client.DramaTagClient;
import com.msd.content.config.RexAdvisoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rex 审核建议编排：标签字典缓存、失败退避、结果裁剪和异步等待。
 *
 * <p>任何异常都转换为 degraded 建议；绝不参与审核状态判定。</p>
 */
@Service
public class RexAdvisoryService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(RexAdvisoryService.class);

    /** 情感证据上限。 */
    private static final int MAX_SENTIMENT_EVIDENCE = 3;
    /** 单个标签证据上限。 */
    private static final int MAX_TAG_EVIDENCE = 2;
    /** 单条证据最大长度。 */
    private static final int MAX_EVIDENCE_CHARS = 120;
    /** 模型/修订号最大长度。 */
    private static final int MAX_VERSION_CHARS = 128;
    /** 标签名最大长度。 */
    private static final int MAX_TAG_CHARS = 32;
    /** 降级标记最大长度。 */
    private static final int MAX_FLAG_CHARS = 64;
    /** 情感标签兜底值。 */
    private static final List<String> FALLBACK_SENTIMENT_LABELS =
            List.of("正向情感", "负向情感");

    // sidecar 客户端
    private final RexAdvisoryClient client;
    // Rex 配置
    private final RexAdvisoryProperties properties;
    // drama 标签字典客户端
    private final DramaTagClient tagClient;
    // advisory 专用线程池
    private final Executor executor;

    // 标签字典缓存
    private volatile List<String> cachedTags = List.of();
    // 标签字典缓存时间
    private volatile long cachedAt = 0L;
    // 失败退避截止时间
    private final AtomicLong backoffUntil = new AtomicLong(0L);

    /**
     * 注入客户端、配置、标签字典客户端和线程池。
     */
    public RexAdvisoryService(RexAdvisoryClient client,
                              RexAdvisoryProperties properties,
                              DramaTagClient tagClient,
                              @Qualifier("reviewAdvisoryExecutor") Executor executor) {
        this.client = client;
        this.properties = properties;
        this.tagClient = tagClient;
        this.executor = executor;
    }

    /**
     * 异步分析；关闭、空文本或退避中直接返回。
     */
    public CompletableFuture<DramaReviewAdvisory> analyzeAsync(String content, Long dramaId) {
        // 1. 开关和空文本不产生建议
        if (!properties.isEnabled() || content == null || content.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        // 2. 失败退避期间不重复打点
        if (System.currentTimeMillis() < backoffUntil.get()) {
            return CompletableFuture.completedFuture(
                    DramaReviewAdvisory.degraded("rex_backoff", 0));
        }
        try {
            return CompletableFuture.supplyAsync(() -> analyze(content, dramaId), executor);
        } catch (Exception ex) {
            log.warn("rex advisory submit failed: dramaId={}, error={}",
                    dramaId, rootMessage(ex));
            return CompletableFuture.completedFuture(
                    DramaReviewAdvisory.degraded("rex_unavailable", 0));
        }
    }

    /**
     * 有界等待；超时取消任务并返回降级建议。
     */
    public DramaReviewAdvisory await(CompletableFuture<DramaReviewAdvisory> future,
                                     long timeoutMs) {
        if (future == null) {
            return null;
        }
        long waitMs = Math.max(1, timeoutMs);
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return DramaReviewAdvisory.degraded("rex_timeout", waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return DramaReviewAdvisory.degraded("rex_timeout", waitMs);
        } catch (ExecutionException ex) {
            log.warn("rex advisory execution failed: error={}", rootMessage(ex));
            return DramaReviewAdvisory.degraded("rex_unavailable", waitMs);
        }
    }

    /**
     * 合规调用结束后等待建议的默认超时。
     */
    public long joinTimeoutMs() {
        return Math.max(0, properties.getJoinTimeoutMs());
    }

    /**
     * 同步执行一次 sidecar 调用并映射为持久化建议。
     */
    private DramaReviewAdvisory analyze(String content, Long dramaId) {
        long startedAt = System.currentTimeMillis();
        try {
            List<String> candidates = tagCandidates();
            RexAnalyzeResponse response = client.analyze(new RexAnalyzeRequest(
                    dramaId == null ? "" : String.valueOf(dramaId),
                    content, sentimentLabels(), candidates));
            if (response == null) {
                markFailure();
                return DramaReviewAdvisory.degraded("rex_unavailable", elapsed(startedAt));
            }
            if (response.degraded()) {
                markFailure();
            } else {
                resetBackoff();
            }
            DramaReviewAdvisory advisory = toAdvisory(response, candidates);
            log.info("drama rex advisory: dramaId={}, degraded={}, flags={}, tags={}, elapsedMs={}",
                    dramaId, advisory.degraded(), advisory.degradedFlags(),
                    advisory.tags().size(), advisory.elapsedMs());
            return advisory;
        } catch (Exception ex) {
            markFailure();
            log.warn("rex advisory failed: dramaId={}, error={}",
                    dramaId, rootMessage(ex));
            return DramaReviewAdvisory.degraded("rex_unavailable", elapsed(startedAt));
        }
    }

    /**
     * 把 sidecar 响应裁剪为可持久化建议。
     */
    private DramaReviewAdvisory toAdvisory(RexAnalyzeResponse response,
                                           List<String> candidates) {
        // 1. 情感：只接受固定枚举，未知标签清空证据
        RexSentiment rawSentiment = response.sentiment();
        String label = normalizeSentimentLabel(rawSentiment == null ? "" : rawSentiment.label());
        String rawLabel = trim(rawSentiment == null ? "" : rawSentiment.rawLabel(), MAX_TAG_CHARS);
        int support = rawSentiment == null ? 0 : Math.max(0, rawSentiment.support());
        List<String> evidence = rawSentiment == null
                ? List.of() : cleanEvidence(rawSentiment.evidence(), MAX_SENTIMENT_EVIDENCE);
        boolean highNegative = rawSentiment != null
                && rawSentiment.highNegative()
                && "negative".equals(label);
        if ("unknown".equals(label)) {
            support = 0;
            evidence = List.of();
            highNegative = false;
        }
        DramaSentimentAdvisory sentiment = new DramaSentimentAdvisory(
                label, rawLabel, highNegative, support, evidence);

        // 2. 标签：只保留请求候选，按原顺序去重，限制数量
        Set<String> allowed = new LinkedHashSet<>(candidates);
        Set<String> seenNames = new LinkedHashSet<>();
        List<DramaTagSuggestion> tags = new ArrayList<>();
        int maxTags = Math.max(0, properties.getMaxTags());
        for (RexTag tag : response.tags()) {
            if (tag == null || tags.size() >= maxTags) {
                continue;
            }
            String name = trim(tag.name(), MAX_TAG_CHARS);
            if (name.isEmpty() || !allowed.contains(name) || !seenNames.add(name)) {
                continue;
            }
            tags.add(new DramaTagSuggestion(name, Math.max(0, tag.support()),
                    cleanEvidence(tag.evidence(), MAX_TAG_EVIDENCE)));
        }

        // 3. 降级标记：保留 sidecar 原始标记，空时补默认值
        List<String> flags = response.degradedFlags().stream()
                .filter(flag -> flag != null && !flag.isBlank())
                .map(flag -> trim(flag, MAX_FLAG_CHARS))
                .distinct()
                .toList();
        if (response.degraded() && flags.isEmpty()) {
            flags = List.of("rex_degraded");
        }

        // 4. 分片统计收敛，避免异常值写入数据库
        int chunks = Math.max(0, response.chunks());
        int failedChunks = Math.min(Math.max(0, response.failedChunks()), chunks);
        return new DramaReviewAdvisory(
                trim(response.model(), MAX_VERSION_CHARS),
                trim(response.revision(), MAX_VERSION_CHARS),
                sentiment, tags, chunks, failedChunks, response.truncated(),
                response.degraded(), flags, Math.max(0, response.elapsedMs()));
    }

    /**
     * 读取标签字典；缓存失效时刷新，失败使用 stale cache 或默认候选。
     */
    private List<String> tagCandidates() {
        long now = System.currentTimeMillis();
        long cacheMs = Math.max(0, properties.getTagCacheMs());
        List<String> cached = cachedTags;
        if (!cached.isEmpty() && now - cachedAt < cacheMs) {
            return cached;
        }
        synchronized (this) {
            if (!cachedTags.isEmpty() && now - cachedAt < cacheMs) {
                return cachedTags;
            }
            List<String> fresh = fetchTagDictionary();
            if (fresh.isEmpty()) {
                fresh = cachedTags.isEmpty()
                        ? normalizeCandidates(properties.getDefaultTagCandidates())
                        : cachedTags;
            }
            cachedTags = fresh;
            cachedAt = now;
            return fresh;
        }
    }

    /**
     * 调用 drama 标签字典，异常时返回空列表由上层回退。
     */
    private List<String> fetchTagDictionary() {
        try {
            Result<List<String>> result = tagClient.tagDictionary();
            if (result != null && result.isSuccess() && result.getData() != null) {
                return normalizeCandidates(result.getData());
            }
        } catch (Exception ex) {
            log.warn("drama tag dictionary failed: error={}", rootMessage(ex));
        }
        return List.of();
    }

    /**
     * 去空白、去重、限长并限制候选数量。
     */
    private List<String> normalizeCandidates(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(0, properties.getMaxTagCandidates());
        if (limit == 0) {
            return List.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String name = value.trim();
            if (name.isEmpty() || name.length() > MAX_TAG_CHARS) {
                continue;
            }
            cleaned.add(name);
            if (cleaned.size() >= limit) {
                break;
            }
        }
        return cleaned.isEmpty() ? List.of() : List.copyOf(cleaned);
    }

    /**
     * 情感标签归一化；缺失时使用默认正/负标签。
     */
    private List<String> sentimentLabels() {
        List<String> configured = properties.getSentimentLabels();
        if (configured == null || configured.isEmpty()) {
            return FALLBACK_SENTIMENT_LABELS;
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : configured) {
            if (value != null && !value.isBlank()) {
                cleaned.add(trim(value, MAX_TAG_CHARS));
            }
        }
        return cleaned.isEmpty() ? FALLBACK_SENTIMENT_LABELS : List.copyOf(cleaned);
    }

    /**
     * 只接受固定情感枚举。
     */
    private String normalizeSentimentLabel(String value) {
        String label = value == null ? "" : value.trim().toLowerCase();
        return switch (label) {
            case "positive", "negative", "mixed" -> label;
            default -> "unknown";
        };
    }

    /**
     * 证据去空白、去重、截断并限制数量。
     */
    private List<String> cleanEvidence(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String evidence = trim(value.replaceAll("\\s+", " "), MAX_EVIDENCE_CHARS);
            if (!evidence.isEmpty()) {
                cleaned.add(evidence);
            }
            if (cleaned.size() >= limit) {
                break;
            }
        }
        return cleaned.isEmpty() ? List.of() : List.copyOf(cleaned);
    }

    /**
     * 截断字符串并去除首尾空白。
     */
    private String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    /**
     * 记录失败并设置退避截止时间。
     */
    private void markFailure() {
        long backoffMs = Math.max(0, properties.getFailureBackoffMs());
        if (backoffMs > 0) {
            backoffUntil.set(System.currentTimeMillis() + backoffMs);
        }
    }

    /**
     * 成功调用后清除退避。
     */
    private void resetBackoff() {
        backoffUntil.set(0L);
    }

    /**
     * 计算耗时。
     */
    private long elapsed(long startedAt) {
        return Math.max(0, System.currentTimeMillis() - startedAt);
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
