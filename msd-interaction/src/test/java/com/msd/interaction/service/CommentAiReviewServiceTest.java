package com.msd.interaction.service;

import com.msd.common.result.Result;
import com.msd.interaction.client.CommentAuditClient;
import com.msd.interaction.dto.CommentAuditResponse;
import com.msd.interaction.dto.CommentAuditVerdict;
import com.msd.interaction.entity.Comment;
import com.msd.interaction.mapper.CommentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论 AI 预审测试。
 */
class CommentAiReviewServiceTest {

    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final CommentAuditClient auditClient = mock(CommentAuditClient.class);
    private final CommentAiReviewService service = new CommentAiReviewService(
            commentMapper, auditClient, JsonMapper.shared());

    /**
     * 审核通过只写 AI 状态 2，不修改人工 status。
     */
    @Test
    void writesPassedStatusWithoutChangingManualStatus() {
        Comment comment = comment();
        when(commentMapper.selectById(1L)).thenReturn(comment);
        when(auditClient.audit(any())).thenReturn(Result.ok(new CommentAuditResponse(
                new CommentAuditVerdict(false, "", List.of()), false, List.of(), 10)));

        service.review(1L);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(commentMapper).markAiReviewing(1L);
        verify(commentMapper).updateAiReview(eq(1L), eq(2), json.capture());
        assertThat(json.getValue()).contains("\"scene\":\"comment\"")
                .contains("\"degraded\":false");
    }

    /**
     * 违规写风险状态 3；降级写失败状态 4。
     */
    @Test
    void mapsRiskAndDegradedStatus() {
        when(commentMapper.selectById(1L)).thenReturn(comment());
        when(auditClient.audit(any())).thenReturn(Result.ok(new CommentAuditResponse(
                new CommentAuditVerdict(true, "含广告", List.of("false_advertising")),
                false, List.of(), 10)));

        service.review(1L);
        verify(commentMapper).updateAiReview(eq(1L), eq(3), any());

        when(auditClient.audit(any())).thenReturn(Result.ok(new CommentAuditResponse(
                null, true, List.of("audit_timeout"), 10)));
        service.review(1L);
        verify(commentMapper).updateAiReview(eq(1L), eq(4), any());
    }

    /**
     * 构造评论。
     */
    private Comment comment() {
        Comment comment = new Comment();
        comment.setId(1L);
        comment.setStatus(1);
        comment.setContent("这条评论内容");
        return comment;
    }
}
