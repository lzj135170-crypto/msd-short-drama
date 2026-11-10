package com.msd.content.audit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.content.ai.AiInputGuard;
import com.msd.content.ai.AiMetrics;
import com.msd.content.ai.AiOutputGuard;
import com.msd.content.ai.AiQuotaService;
import com.msd.content.config.DramaPrecheckProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 创作者提交前的 AI 预检。
 *
 * <p>只做合规判断和文案建议，不写 ai_review_status，不写标签关系表。
 * 合规降级时 verdict=null，绝不自动判定通过；文案建议失败不影响合规结论。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.precheck", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DramaPrecheckService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DramaPrecheckService.class);

    /** 审核场景。 */
    private static final String SCENE = "drama_precheck";
    /** 送审文本最大长度，与 StructuredAuditService 的限制一致。 */
    private static final int MAX_CONTENT_CHARS = 5000;
    /** 降级标记：送审内容为空。 */
    private static final String FLAG_EMPTY_CONTENT = "precheck_empty_content";
    /** 降级标记：结构化审核未启用。 */
    private static final String FLAG_AUDIT_DISABLED = "audit_disabled";

    // 结构化审核服务，审核关闭时不存在
    private final ObjectProvider<StructuredAuditService> auditServiceProvider;
    // 文案建议服务
    private final DramaPrecheckSuggestionService suggestionService;
    // Rex 标签建议
    private final RexAdvisoryService rexAdvisoryService;
    // 输入防护
    private final AiInputGuard inputGuard;
    // 输出过滤
    private final AiOutputGuard outputGuard;
    // 按用户按天的配额
    private final AiQuotaService quotaService;
    // 预检配置
    private final DramaPrecheckProperties properties;
    // AI 链路指标
    private final AiMetrics metrics;
    // 按内容哈希缓存，避免重复调用模型
    private final Cache<String, DramaPrecheckResponse> cache;

    /**
     * 注入预检依赖，并按配置创建内容缓存。
     */
    public DramaPrecheckService(ObjectProvider<StructuredAuditService> auditServiceProvider,
                                DramaPrecheckSuggestionService suggestionService,
                                RexAdvisoryService rexAdvisoryService,
                                AiInputGuard inputGuard,
                                AiOutputGuard outputGuard,
                                AiQuotaService quotaService,
                                DramaPrecheckProperties properties,
                                AiMetrics metrics) {
        this.auditServiceProvider = auditServiceProvider;
        this.suggestionService = suggestionService;
        this.rexAdvisoryService = rexAdvisoryService;
        this.inputGuard = inputGuard;
        this.outputGuard = outputGuard;
        this.quotaService = quotaService;
        this.properties = properties;
        this.metrics = metrics;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(properties.getCacheMinutes(), 1)))
                .maximumSize(Math.max(properties.getCacheMaxEntries(), 1))
                .build();
    }

    /**
     * 执行一次预检。
     *
     * @param userId  当前创作者
     * @param request 标题、副标题、简介和分集标题
     * @return 合规结论和可人工采纳的文案建议
     */
    public DramaPrecheckResponse precheck(Long userId, DramaPrecheckRequest request) {
        long startedAt = System.currentTimeMillis();
        String content = buildContent(request);
        inputGuard.validateAuditContent(content);

        // 1. 相同内容直接复用上一次结果，不重复消耗配额和模型调用
        String cacheKey = contentHash(content);
        DramaPrecheckResponse cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            metrics.recordRequest(SCENE, cached.degraded(), cached.degradedFlags(), 0);
            return cached.withCacheHit(newRequestId());
        }

        // 2. 缓存未命中才消耗每日配额
        consumeQuota(userId);

        // 3. 合规判断和文案建议并行，合规失败仍返回建议
        CompletableFuture<DramaPrecheckSuggestion> suggestionFuture = startSuggestion(content);
        CompletableFuture<DramaReviewAdvisory> advisoryFuture = startAdvisory(content);
        AuditCheckResponse audit = runAudit(content);

        // 4. 有界等待建议；超时只标记建议降级
        DramaPrecheckSuggestion suggestion = awaitSuggestion(suggestionFuture);
        DramaReviewAdvisory advisory = awaitAdvisory(advisoryFuture);

        boolean degraded = audit.degraded() || audit.verdict() == null;
        boolean suggestionDegraded = suggestion == null;
        List<String> titleSuggestions = sanitizeList(
                suggestion == null ? List.of() : suggestion.titleSuggestions(),
                properties.getMaxTitleSuggestions());
        List<String> subtitleSuggestions = sanitizeList(
                suggestion == null ? List.of() : suggestion.subtitleSuggestions(),
                properties.getMaxSubtitleSuggestions());
        List<String> tagSuggestions = mergeTags(suggestion, advisory);
        List<String> tips = sanitizeList(
                suggestion == null ? List.of() : suggestion.tips(),
                properties.getMaxTips());

        // 5. 组装响应；合规降级时不写缓存，下一次可以重试
        DramaPrecheckResponse response = new DramaPrecheckResponse(
                newRequestId(), audit.verdict(), degraded, audit.degradedFlags(),
                suggestionDegraded, titleSuggestions, subtitleSuggestions, tagSuggestions,
                tips, false, System.currentTimeMillis() - startedAt);
        if (!degraded && !suggestionDegraded) {
            cache.put(cacheKey, response);
        }
        metrics.recordRequest(SCENE, degraded, audit.degradedFlags(),
                response.elapsedMs());
        return response;
    }

    /**
     * 消耗每日配额，超限返回 429。
     */
    private void consumeQuota(Long userId) {
        AiQuotaService.QuotaResult quota = quotaService.consume(
                SCENE, userId, properties.getDailyLimit());
        if (!quota.allowed()) {
            throw new BizException(ResultCode.TOO_MANY_REQUESTS,
                    "今天的 AI 预检次数已用完，请明天再试");
        }
    }

    /**
     * 启动文案建议；异常时降级为 null，不阻断合规判断。
     */
    private CompletableFuture<DramaPrecheckSuggestion> startSuggestion(String content) {
        try {
            return suggestionService.suggestAsync(content);
        } catch (Exception e) {
            log.warn("drama precheck suggestion start failed: error={}", rootMessage(e));
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 启动 Rex 标签建议；异常时返回空建议。
     */
    private CompletableFuture<DramaReviewAdvisory> startAdvisory(String content) {
        try {
            return rexAdvisoryService.analyzeAsync(content, null);
        } catch (Exception e) {
            log.warn("drama precheck rex start failed: error={}", rootMessage(e));
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 执行合规审核；模型异常转换为降级响应。
     */
    private AuditCheckResponse runAudit(String content) {
        if (content.isBlank()) {
            return new AuditCheckResponse(null, true, List.of(FLAG_EMPTY_CONTENT), 0);
        }
        StructuredAuditService auditService = auditServiceProvider.getIfAvailable();
        if (auditService == null) {
            return new AuditCheckResponse(null, true, List.of(FLAG_AUDIT_DISABLED), 0);
        }
        try {
            return auditService.audit(new AuditCheckRequest(content, SCENE));
        } catch (Exception e) {
            log.warn("drama precheck audit failed: error={}", rootMessage(e));
            return new AuditCheckResponse(
                    null, true, List.of(StructuredAuditService.FLAG_UNAVAILABLE), 0);
        }
    }

    /**
     * 有界等待文案建议。
     */
    private DramaPrecheckSuggestion awaitSuggestion(
            CompletableFuture<DramaPrecheckSuggestion> future) {
        if (future == null) {
            return null;
        }
        try {
            return future.get(Math.max(properties.getSuggestionTimeoutMs(), 1),
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("drama precheck suggestion timeout: timeoutMs={}",
                    properties.getSuggestionTimeoutMs());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.warn("drama precheck suggestion failed: error={}", rootMessage(e));
            return null;
        }
    }

    /**
     * 有界等待 Rex 建议。
     */
    private DramaReviewAdvisory awaitAdvisory(
            CompletableFuture<DramaReviewAdvisory> future) {
        if (future == null) {
            return null;
        }
        try {
            return rexAdvisoryService.await(future, rexAdvisoryService.joinTimeoutMs());
        } catch (Exception e) {
            log.warn("drama precheck rex failed: error={}", rootMessage(e));
            return null;
        }
    }

    /**
     * 合并模型标签和 Rex 标签，过滤空值并去重。
     */
    private List<String> mergeTags(DramaPrecheckSuggestion suggestion,
                                   DramaReviewAdvisory advisory) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (suggestion != null) {
            tags.addAll(sanitizeList(suggestion.tagSuggestions(),
                    properties.getMaxTagSuggestions()));
        }
        if (advisory != null && advisory.tags() != null) {
            for (DramaTagSuggestion tag : advisory.tags()) {
                if (tag == null || tag.name() == null || tag.name().isBlank()) {
                    continue;
                }
                addSanitized(tags, tag.name());
                if (tags.size() >= properties.getMaxTagSuggestions()) {
                    break;
                }
            }
        }
        return List.copyOf(tags);
    }

    /**
     * 逐条过滤建议内容并限制数量。
     */
    private List<String> sanitizeList(List<String> values, int maxItems) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null || maxItems <= 0) {
            return List.of();
        }
        for (String value : values) {
            addSanitized(result, value);
            if (result.size() >= maxItems) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /**
     * 过滤单条建议并去重。
     */
    private void addSanitized(LinkedHashSet<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String clean = outputGuard.sanitize(value.replace('\n', ' ').trim(),
                properties.getMaxSuggestionChars());
        if (!clean.isBlank()) {
            target.add(clean);
        }
    }

    /**
     * 拼接送审文本，超长时截断。
     */
    private String buildContent(DramaPrecheckRequest request) {
        List<String> lines = new ArrayList<>();
        appendLine(lines, "标题", request.title());
        appendLine(lines, "副标题", request.subtitle());
        appendLine(lines, "简介", request.description());
        if (!request.episodeTitles().isEmpty()) {
            List<String> titles = request.episodeTitles().stream()
                    .filter(title -> title != null && !title.isBlank())
                    .map(String::trim)
                    .toList();
            appendLine(lines, "分集标题", String.join("；", titles));
        }
        String content = String.join("\n", lines).trim();
        return content.length() <= MAX_CONTENT_CHARS
                ? content : content.substring(0, MAX_CONTENT_CHARS);
    }

    /**
     * 追加非空字段。
     */
    private void appendLine(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + "：" + value.trim());
        }
    }

    /**
     * 内容哈希作为缓存键，避免原文进入缓存键。
     */
    private String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    /**
     * 每次请求生成新的 requestId。
     */
    private String newRequestId() {
        return UUID.randomUUID().toString();
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
