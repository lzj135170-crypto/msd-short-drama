package com.msd.interaction.service;

import com.msd.common.exception.BizException;
import com.msd.interaction.dto.AiFeedbackRequest;
import com.msd.interaction.dto.AiFeedbackResult;
import com.msd.interaction.entity.AiFeedback;
import com.msd.interaction.mapper.AiFeedbackMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 反馈幂等、校验和指标测试。
 */
class AiFeedbackServiceTest {

    private final AiFeedbackMapper feedbackMapper = mock(AiFeedbackMapper.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AiFeedbackService service =
            new AiFeedbackService(feedbackMapper, meterRegistry);

    /**
     * 首次提交写入反馈表并记录指标。
     */
    @Test
    void insertsNewFeedbackAndCountsMetric() {
        when(feedbackMapper.insertIgnore(any())).thenAnswer(invocation -> {
            AiFeedback feedback = invocation.getArgument(0);
            feedback.setId(9L);
            return 1;
        });

        AiFeedbackResult result = service.submit(1003L, request(1));

        assertThat(result.id()).isEqualTo(9L);
        assertThat(result.duplicate()).isFalse();
        ArgumentCaptor<AiFeedback> captor = ArgumentCaptor.forClass(AiFeedback.class);
        verify(feedbackMapper).insertIgnore(captor.capture());
        assertThat(captor.getValue().getScene()).isEqualTo("RAG_SEARCH");
        assertThat(captor.getValue().getRequestId()).isEqualTo("req-1");
        assertThat(captor.getValue().getReason()).isEqualTo("INACCURATE");
        assertThat(captor.getValue().getComment()).isEmpty();
        assertThat(meterRegistry.get("ai_feedback_total")
                .tags("scene", "RAG_SEARCH", "rating", "1")
                .counter().count()).isEqualTo(1.0);
    }

    /**
     * 同一用户、场景和 requestId 重复提交返回已有记录。
     */
    @Test
    void duplicateReturnsExistingFeedback() {
        when(feedbackMapper.insertIgnore(any())).thenReturn(0);
        AiFeedback existing = new AiFeedback();
        existing.setId(7L);
        existing.setRating(-1);
        when(feedbackMapper.selectByUserSceneRequest(1003L, "RAG_SEARCH", "req-1"))
                .thenReturn(existing);

        AiFeedbackResult result = service.submit(1003L, request(-1));

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.duplicate()).isTrue();
    }

    /**
     * rating 只允许 1 和 -1。
     */
    @Test
    void rejectsInvalidRating() {
        assertThatThrownBy(() -> service.submit(1003L, request(0)))
                .isInstanceOf(BizException.class);
    }

    /**
     * 创作者采纳 Copilot 文案可作为正向反馈上报。
     */
    @Test
    void acceptsCreatorCopyAdoption() {
        when(feedbackMapper.insertIgnore(any())).thenAnswer(invocation -> {
            AiFeedback feedback = invocation.getArgument(0);
            feedback.setId(11L);
            return 1;
        });

        AiFeedbackResult result = service.submit(1003L, new AiFeedbackRequest(
                "creator_copy", "req-copy", 1, "adopt", "采纳标题", 5001L, null));

        assertThat(result.id()).isEqualTo(11L);
        ArgumentCaptor<AiFeedback> captor = ArgumentCaptor.forClass(AiFeedback.class);
        verify(feedbackMapper).insertIgnore(captor.capture());
        assertThat(captor.getValue().getScene()).isEqualTo("CREATOR_COPY");
        assertThat(captor.getValue().getReason()).isEqualTo("ADOPT");
    }

    /**
     * 场景只允许已登记的 AI 场景。
     */
    @Test
    void rejectsUnknownScene() {
        AiFeedbackRequest request = new AiFeedbackRequest(
                "OTHER", "req-1", 1, "", "", 5001L, "s1");

        assertThatThrownBy(() -> service.submit(1003L, request))
                .isInstanceOf(BizException.class);
    }

    /**
     * 构造反馈请求。
     */
    private AiFeedbackRequest request(int rating) {
        return new AiFeedbackRequest(
                "rag_search", "req-1", rating, "inaccurate", "", 5001L, "s1");
    }
}
