package com.msd.interaction.service;

import com.msd.common.result.Result;
import com.msd.interaction.client.CommentSummaryClient;
import com.msd.interaction.dto.CommentSummaryClientRequest;
import com.msd.interaction.dto.CommentSummaryClientResponse;
import com.msd.interaction.dto.CommentSummaryView;
import com.msd.interaction.entity.Comment;
import com.msd.interaction.mapper.CommentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论课代表编排测试。
 */
class CommentSummaryServiceTest {

    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final CommentSummaryClient summaryClient = mock(CommentSummaryClient.class);
    private final CommentSummaryCache summaryCache = mock(CommentSummaryCache.class);
    private final CommentSummaryService service = new CommentSummaryService(
            commentMapper, summaryClient, summaryCache);

    /**
     * 剧透评论不进入摘要，代表评论按 ID 映射回原文。
     */
    @Test
    void filtersSpoilersAndMapsRepresentatives() {
        when(commentMapper.selectTopComments(5001L, null, 50)).thenReturn(List.of(
                comment(1L, "大结局凶手是男主", 99L),
                comment(2L, "节奏很好", 20L),
                comment(3L, "演员不错", 10L)));
        when(summaryClient.summarize(any())).thenReturn(Result.ok(
                new CommentSummaryClientResponse("大家觉得节奏不错", "positive",
                        List.of(2L), false, List.of(), 100)));

        CommentSummaryView view = service.getSummary(5001L, null);

        assertThat(view.degraded()).isFalse();
        assertThat(view.summary()).isEqualTo("大家觉得节奏不错");
        assertThat(view.representatives()).extracting(
                        CommentSummaryView.RepresentativeComment::commentId)
                .containsExactly(2L);
        ArgumentCaptor<CommentSummaryClientRequest> captor =
                ArgumentCaptor.forClass(CommentSummaryClientRequest.class);
        verify(summaryClient).summarize(captor.capture());
        assertThat(captor.getValue().comments())
                .extracting(CommentSummaryClientRequest.CommentInput::commentId)
                .containsExactly(2L, 3L);
    }

    /**
     * 没有可见评论时降级为空。
     */
    @Test
    void returnsEmptyWhenNoComments() {
        when(commentMapper.selectTopComments(5001L, null, 50)).thenReturn(List.of());

        CommentSummaryView view = service.getSummary(5001L, null);

        assertThat(view.degraded()).isTrue();
        assertThat(view.degradedFlags()).containsExactly(CommentSummaryService.FLAG_EMPTY);
    }

    /**
     * 下游异常时降级，不阻断评论列表。
     */
    @Test
    void degradesWhenClientFails() {
        when(commentMapper.selectTopComments(5001L, null, 50))
                .thenReturn(List.of(comment(2L, "节奏很好", 20L)));
        when(summaryClient.summarize(any())).thenThrow(new IllegalStateException("content down"));

        CommentSummaryView view = service.getSummary(5001L, null);

        assertThat(view.degraded()).isTrue();
        assertThat(view.degradedFlags()).containsExactly(CommentSummaryService.FLAG_UNAVAILABLE);
    }

    /**
     * 构造评论。
     */
    private Comment comment(Long id, String content, Long likes) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setContent(content);
        comment.setLikeCount(likes);
        return comment;
    }
}
