package com.msd.interaction.service;

import com.msd.common.result.Result;
import com.msd.interaction.client.CommentAuditClient;
import com.msd.interaction.dto.CommentAiReviewPayload;
import com.msd.interaction.dto.CommentAuditRequest;
import com.msd.interaction.dto.CommentAuditResponse;
import com.msd.interaction.dto.CommentAuditVerdict;
import com.msd.interaction.entity.Comment;
import com.msd.interaction.event.CommentCreatedEvent;
import com.msd.interaction.mapper.CommentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评论 AI 预审。
 *
 * <p>只写 ai_review_status/ai_review_result/ai_review_time，绝不修改人工 status；
 * 默认为异步建议，管理端仍按风险筛选和人工处置。</p>
 */
@Service
public class CommentAiReviewService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CommentAiReviewService.class);

    /** 审核场景。 */
    private static final String SCENE = "comment";
    /** 结果 JSON 最大长度。 */
    private static final int MAX_RESULT_CHARS = 3_900;
    /** AI 通过。 */
    private static final int STATUS_PASSED = 2;
    /** AI 风险。 */
    private static final int STATUS_RISK = 3;
    /** AI 失败。 */
    private static final int STATUS_FAILED = 4;
    /** 序列化失败标记。 */
    private static final String FLAG_SERIALIZE = "comment_audit_serialize_error";
    /** 下游失败标记。 */
    private static final String FLAG_UNAVAILABLE = "comment_audit_unavailable";

    // 评论 Mapper
    private final CommentMapper commentMapper;
    // content 审核客户端
    private final CommentAuditClient auditClient;
    // JSON 序列化
    private final JsonMapper jsonMapper;

    /**
     * 注入 Mapper、审核客户端和序列化器。
     */
    public CommentAiReviewService(CommentMapper commentMapper,
                                  CommentAuditClient auditClient,
                                  JsonMapper jsonMapper) {
        this.commentMapper = commentMapper;
        this.auditClient = auditClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 评论创建事务提交后异步预审。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentCreated(CommentCreatedEvent event) {
        if (event != null && event.commentId() != null) {
            review(event.commentId());
        }
    }

    /**
     * 异步重试入口，供管理端手动触发。
     */
    @Async
    public void reviewAsync(Long commentId) {
        review(commentId);
    }

    /**
     * 执行一次评论预审；只更新 AI 三列。
     */
    public void review(Long commentId) {
        // 1. 评论不存在或已删除时跳过
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || Integer.valueOf(3).equals(comment.getStatus())) {
            return;
        }
        commentMapper.markAiReviewing(commentId);
        long startedAt = System.currentTimeMillis();

        // 2. 调用 content 结构化审核
        CommentAuditResponse response;
        try {
            Result<CommentAuditResponse> result = auditClient.audit(
                    new CommentAuditRequest(comment.getContent(), SCENE));
            if (result == null || !result.isSuccess() || result.getData() == null) {
                writeResult(commentId, STATUS_FAILED,
                        payload(null, true, List.of(FLAG_UNAVAILABLE), startedAt));
                return;
            }
            response = result.getData();
        } catch (Exception e) {
            log.warn("comment ai review failed: commentId={}, error={}",
                    commentId, rootMessage(e));
            writeResult(commentId, STATUS_FAILED,
                    payload(null, true, List.of(FLAG_UNAVAILABLE), startedAt));
            return;
        }

        // 3. 降级写失败；违规写风险；其余写通过，均不改 status
        boolean degraded = response.degraded() || response.verdict() == null;
        int status = degraded ? STATUS_FAILED
                : (response.verdict().isViolation() ? STATUS_RISK : STATUS_PASSED);
        writeResult(commentId, status, payload(response.verdict(), degraded,
                response.degradedFlags(), response.elapsedMs()));
        log.info("comment ai review: commentId={}, status={}, degraded={}, elapsedMs={}",
                commentId, status, degraded, response.elapsedMs());
    }

    /**
     * 构造待落库 JSON。
     */
    private CommentAiReviewPayload payload(CommentAuditVerdict verdict, boolean degraded,
                                           List<String> flags, long elapsedMs) {
        return new CommentAiReviewPayload(SCENE, verdict, degraded,
                flags == null ? List.of() : flags, Math.max(elapsedMs, 0),
                OffsetDateTime.now().toString());
    }

    /**
     * 序列化并写回；超长时退化为精简结果，保证 JSON 合法。
     */
    private void writeResult(Long commentId, int status, CommentAiReviewPayload payload) {
        String json;
        try {
            json = jsonMapper.writeValueAsString(payload);
            if (json.length() > MAX_RESULT_CHARS) {
                json = jsonMapper.writeValueAsString(payload(null, true,
                        List.of("comment_audit_result_truncated"),
                        payload.elapsedMs()));
            }
        } catch (Exception e) {
            json = "{\"scene\":\"comment\",\"degraded\":true,"
                    + "\"degradedFlags\":[\"" + FLAG_SERIALIZE + "\"]}";
        }
        commentMapper.updateAiReview(commentId, status, json);
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
