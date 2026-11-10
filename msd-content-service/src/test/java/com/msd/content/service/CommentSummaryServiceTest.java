package com.msd.content.service;

import com.msd.content.ai.AiMetrics;
import com.msd.content.ai.AiOutputGuard;
import com.msd.content.dto.CommentSummaryRequest;
import com.msd.content.dto.CommentSummaryResponse;
import com.msd.content.dto.CommentSummaryVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评论课代表摘要测试。
 */
class CommentSummaryServiceTest {

    /**
     * 没有评论时直接降级，不调用模型。
     */
    @Test
    void returnsEmptyWhenNoComments() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        CommentSummaryService service = service(chatClient);

        CommentSummaryResponse response = service.summarize(
                new CommentSummaryRequest(5001L, null, List.of()));

        assertThat(response.degraded()).isTrue();
        assertThat(response.degradedFlags()).containsExactly(
                CommentSummaryService.FLAG_EMPTY);
        assertThat(response.summary()).isEmpty();
    }

    /**
     * 模型返回的评论 ID 必须属于输入，并过滤联系方式。
     */
    @Test
    void filtersRepresentativeIdsAndMasksContact() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString())
                .call().entity(CommentSummaryVerdict.class))
                .thenReturn(new CommentSummaryVerdict(
                        "大家觉得节奏不错，联系 13800138000",
                        "positive",
                        List.of(2L, 99L)));
        CommentSummaryService service = service(chatClient);

        CommentSummaryResponse response = service.summarize(new CommentSummaryRequest(
                5001L, null,
                List.of(
                        new CommentSummaryRequest.CommentInput(1L, "节奏很好", 10L),
                        new CommentSummaryRequest.CommentInput(2L, "演员不错", 8L))));

        assertThat(response.degraded()).isFalse();
        assertThat(response.summary())
                .doesNotContain("13800138000")
                .contains("[联系方式已隐藏]");
        assertThat(response.representativeCommentIds()).containsExactly(2L);
    }

    /**
     * 模型调用异常时返回降级响应。
     */
    @Test
    void degradesWhenModelFails() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString())
                .call().entity(CommentSummaryVerdict.class))
                .thenThrow(new IllegalStateException("llm down"));
        CommentSummaryService service = service(chatClient);

        CommentSummaryResponse response = service.summarize(new CommentSummaryRequest(
                5001L, null,
                List.of(new CommentSummaryRequest.CommentInput(1L, "好看", 3L))));

        assertThat(response.degraded()).isTrue();
        assertThat(response.degradedFlags()).containsExactly(
                CommentSummaryService.FLAG_UNAVAILABLE);
    }

    /**
     * 构造使用较短超时的服务。
     */
    private CommentSummaryService service(ChatClient chatClient) {
        return new CommentSummaryService(chatClient, new AiOutputGuard(),
                new AiMetrics(null), 3000);
    }
}
