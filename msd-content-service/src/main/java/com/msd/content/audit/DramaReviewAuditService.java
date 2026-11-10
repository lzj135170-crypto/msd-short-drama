package com.msd.content.audit;

import com.msd.common.dto.DramaAiReviewResultRequest;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.content.ai.AiInputGuard;
import com.msd.content.ai.AiMetrics;
import com.msd.content.client.DramaReviewClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 短剧 AI 审核编排。
 *
 * <p>从 drama 拉取待审短剧，调用 {@link StructuredAuditService}，再把结果写回
 * ai_review_*。AI 不修改 review_status；降级一律写失败，绝不自动通过。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DramaReviewAuditService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DramaReviewAuditService.class);

    /** 单批最大审核数量。 */
    private static final int MAX_LIMIT = 20;
    /** 默认批量大小。 */
    private static final int DEFAULT_LIMIT = 5;
    /** 送审文本最大长度，与审核接口限制一致。 */
    private static final int MAX_CONTENT_CHARS = 5000;
    /** 审核场景。 */
    private static final String SCENE = "drama_description";
    /** 降级标记：drama 下游不可用。 */
    private static final String FLAG_DRAMA = "drama";
    /** 降级标记：审核内容为空。 */
    private static final String FLAG_EMPTY_CONTENT = "audit_empty_content";
    /** 降级标记：审核内容命中输入防护。 */
    private static final String FLAG_INPUT_GUARD = "audit_input_guard";
    /** 降级标记：审核结果序列化失败。 */
    private static final String FLAG_SERIALIZE = "audit_serialize_error";
    /** 写回 JSON 上限，为 drama 侧 8000 字限制留出余量。 */
    private static final int MAX_RESULT_CHARS = 7_800;

    // 短剧审核内部客户端
    private final DramaReviewClient reviewClient;
    // 结构化审核服务
    private final StructuredAuditService auditService;
    // Rex 情感/标签建议
    private final RexAdvisoryService rexAdvisoryService;
    // Jackson 3 JSON 序列化
    private final JsonMapper jsonMapper;
    // 送审文本输入防护
    private final AiInputGuard inputGuard;
    // AI 链路指标
    private final AiMetrics metrics;

    /**
     * 注入审核客户端、审核服务、Rex 建议、序列化器和输入防护。
     */
    public DramaReviewAuditService(DramaReviewClient reviewClient,
                                   StructuredAuditService auditService,
                                   RexAdvisoryService rexAdvisoryService,
                                   JsonMapper jsonMapper,
                                   AiInputGuard inputGuard,
                                   AiMetrics metrics) {
        this.reviewClient = reviewClient;
        this.auditService = auditService;
        this.rexAdvisoryService = rexAdvisoryService;
        this.jsonMapper = jsonMapper;
        this.inputGuard = inputGuard;
        this.metrics = metrics;
    }

    /**
     * 批量审核待审短剧；默认不包含 AI 失败项。
     */
    public DramaReviewAuditBatchResult auditPending(Integer limit) {
        return auditPending(limit, false);
    }

    /**
     * 批量审核待审短剧；单条失败不中断整批。
     *
     * @param includeFailed true 时同时重试 ai_review_status=4 的短剧
     */
    public DramaReviewAuditBatchResult auditPending(Integer limit, boolean includeFailed) {
        long startedAt = System.currentTimeMillis();
        // 1. 收敛批量大小
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));

        // 2. 从 drama 拉取候选；下游不可用时整体降级
        List<DramaReviewCandidateDTO> candidates;
        try {
            Result<List<DramaReviewCandidateDTO>> response =
                    reviewClient.pending(size, includeFailed);
            if (response == null || !response.isSuccess()) {
                return degradedBatch(startedAt);
            }
            candidates = response.getData() == null ? List.of() : response.getData();
        } catch (Exception e) {
            log.warn("drama review candidates failed: error={}", rootMessage(e));
            return degradedBatch(startedAt);
        }

        // 3. 逐条审核并统计
        List<DramaReviewAuditItem> items = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        int audited = 0;
        int passed = 0;
        int rejected = 0;
        int failed = 0;
        int skipped = 0;
        for (DramaReviewCandidateDTO candidate : candidates) {
            DramaReviewAuditItem item = auditCandidate(candidate);
            if (item == null) {
                skipped++;
                continue;
            }
            items.add(item);
            flags.addAll(item.degradedFlags());
            if (!item.updated()) {
                skipped++;
                continue;
            }
            audited++;
            if (Integer.valueOf(2).equals(item.aiReviewStatus())) {
                passed++;
            } else if (Integer.valueOf(3).equals(item.aiReviewStatus())) {
                rejected++;
            } else {
                failed++;
            }
        }

        // 4. 返回批次统计，调用方据此判断是否需要人工复核
        DramaReviewAuditBatchResult result = new DramaReviewAuditBatchResult(
                candidates.size(), audited, passed, rejected, failed, skipped,
                List.copyOf(flags), items);
        log.info("drama review audit: scanned={}, audited={}, passed={}, rejected={}, "
                        + "failed={}, skipped={}, flags={}",
                result.scanned(), result.audited(), result.passed(), result.rejected(),
                result.failed(), result.skipped(), result.degradedFlags());
        metrics.recordRequest("audit", !flags.isEmpty(), flags,
                System.currentTimeMillis() - startedAt);
        return result;
    }

    /**
     * 审核单部短剧；不存在或不在待审状态时返回 null。
     */
    public DramaReviewAuditItem auditOne(Long dramaId) {
        // 1. 参数非法直接返回
        if (dramaId == null || dramaId <= 0) {
            return null;
        }
        // 2. 查询待审详情；drama 不可用时返回失败项
        Result<DramaReviewCandidateDTO> response;
        try {
            response = reviewClient.detail(dramaId);
        } catch (Exception e) {
            log.warn("drama review detail failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
            return new DramaReviewAuditItem(dramaId, 4, false, List.of(FLAG_DRAMA));
        }
        if (response == null || !response.isSuccess() || response.getData() == null) {
            return null;
        }
        return auditCandidate(response.getData());
    }

    /**
     * 审核单条候选并写回结果。
     */
    private DramaReviewAuditItem auditCandidate(DramaReviewCandidateDTO candidate) {
        // 1. 过滤无效候选
        if (candidate == null || candidate.dramaId() == null) {
            return null;
        }
        Long dramaId = candidate.dramaId();
        String content = buildContent(candidate);
        String requestId = UUID.randomUUID().toString();

        // 2. 先做输入防护；命中注入时不启动 Rex 或审核模型
        AuditCheckResponse response = null;
        CompletableFuture<DramaReviewAdvisory> advisoryFuture =
                CompletableFuture.completedFuture(null);
        if (content.isBlank()) {
            response = new AuditCheckResponse(null, true, List.of(FLAG_EMPTY_CONTENT), 0);
        } else {
            try {
                inputGuard.validateAuditContent(content);
            } catch (BizException e) {
                log.warn("drama review input rejected: dramaId={}, reason={}",
                        dramaId, e.getMessage());
                response = new AuditCheckResponse(null, true, List.of(FLAG_INPUT_GUARD), 0);
            }
            // 3. 内容通过防护后，启动 Rex 建议并与合规调用并行
            if (response == null) {
                advisoryFuture = startAdvisory(content, dramaId);
                try {
                    response = auditService.audit(new AuditCheckRequest(content, SCENE));
                } catch (Exception e) {
                    log.warn("drama review audit failed: dramaId={}, error={}",
                            dramaId, rootMessage(e));
                    response = new AuditCheckResponse(
                            null, true, List.of(StructuredAuditService.FLAG_UNAVAILABLE), 0);
                }
            }
        }

        // 4. 降级写 4，违规写 3，未违规写 2；Rex 建议不参与状态映射
        int status = response.degraded() || response.verdict() == null
                ? 4 : (response.verdict().isViolation() ? 3 : 2);

        // 5. 有界等待建议；失败只写降级 advisory，不改变合规状态
        DramaReviewAdvisory advisory = awaitAdvisory(advisoryFuture, dramaId);

        // 6. 序列化完整结果；超限时按证据、标签、建议逐级裁剪
        String json;
        try {
            json = serializeResult(new DramaAiReviewResult(
                    SCENE, response.verdict(), response.degraded(),
                    response.degradedFlags(), response.elapsedMs(),
                    OffsetDateTime.now().toString(), advisory, requestId));
        } catch (Exception e) {
            log.warn("drama review result serialize failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
            return new DramaReviewAuditItem(
                    dramaId, 4, false, List.of(FLAG_SERIALIZE), requestId);
        }

        // 7. 条件写回；人工审核已推进时 updated=false
        boolean updated = false;
        try {
            Result<Boolean> writeback = reviewClient.writeAiResult(
                    dramaId, new DramaAiReviewResultRequest(status, json));
            updated = writeback != null && writeback.isSuccess()
                    && Boolean.TRUE.equals(writeback.getData());
        } catch (Exception e) {
            log.warn("drama review writeback failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
        }
        return new DramaReviewAuditItem(
                dramaId, status, updated, response.degradedFlags(), requestId);
    }

    /**
     * 启动异步建议；任何异常都转为降级建议。
     */
    private CompletableFuture<DramaReviewAdvisory> startAdvisory(String content, Long dramaId) {
        if (content.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return rexAdvisoryService.analyzeAsync(content, dramaId);
        } catch (Exception e) {
            log.warn("drama rex advisory start failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
            return CompletableFuture.completedFuture(
                    DramaReviewAdvisory.degraded("rex_unavailable", 0));
        }
    }

    /**
     * 有界等待建议；超时或异常只返回降级建议。
     */
    private DramaReviewAdvisory awaitAdvisory(
            CompletableFuture<DramaReviewAdvisory> future, Long dramaId) {
        if (future == null) {
            return null;
        }
        try {
            return rexAdvisoryService.await(future, rexAdvisoryService.joinTimeoutMs());
        } catch (Exception e) {
            log.warn("drama rex advisory await failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
            return DramaReviewAdvisory.degraded("rex_unavailable", 0);
        }
    }

    /**
     * 序列化审核结果，超限时逐级裁剪 advisory。
     */
    private String serializeResult(DramaAiReviewResult result) throws Exception {
        String json = jsonMapper.writeValueAsString(result);
        if (json.length() <= MAX_RESULT_CHARS) {
            return json;
        }
        DramaReviewAdvisory advisory = result.advisory();
        if (advisory != null) {
            json = jsonMapper.writeValueAsString(result.withAdvisory(advisory.withoutEvidence()));
            if (json.length() <= MAX_RESULT_CHARS) {
                return json;
            }
            json = jsonMapper.writeValueAsString(result.withAdvisory(advisory.withoutTags()));
            if (json.length() <= MAX_RESULT_CHARS) {
                return json;
            }
            json = jsonMapper.writeValueAsString(result.withAdvisory(null));
            if (json.length() <= MAX_RESULT_CHARS) {
                return json;
            }
        }
        throw new IllegalStateException("AI review result exceeds " + MAX_RESULT_CHARS + " chars");
    }

    /**
     * 拼接标题、副标题和简介作为送审文本。
     */
    private String buildContent(DramaReviewCandidateDTO candidate) {
        StringBuilder content = new StringBuilder();
        appendLine(content, candidate.title());
        appendLine(content, candidate.subtitle());
        appendLine(content, candidate.description());
        String text = content.toString().trim();
        return text.length() <= MAX_CONTENT_CHARS
                ? text : text.substring(0, MAX_CONTENT_CHARS);
    }

    /**
     * 追加非空行。
     */
    private void appendLine(StringBuilder content, String value) {
        if (value != null && !value.isBlank()) {
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append(value.trim());
        }
    }

    /**
     * drama 不可用时的空批次。
     */
    private DramaReviewAuditBatchResult degradedBatch(long startedAt) {
        metrics.recordRequest("audit", true, List.of(FLAG_DRAMA),
                System.currentTimeMillis() - startedAt);
        return new DramaReviewAuditBatchResult(
                0, 0, 0, 0, 0, 0, List.of(FLAG_DRAMA), List.of());
    }

    /**
     * 取最内层异常信息。
     */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
