package com.msd.interaction.service;

import com.msd.common.exception.BizException;
import com.msd.interaction.dto.CommentRequest;
import com.msd.interaction.dto.CommentResult;
import com.msd.interaction.dto.LikeResult;
import com.msd.interaction.entity.Comment;
import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.mapper.CommentMapper;
import com.msd.interaction.mapper.OutboxEventMapper;
import com.msd.interaction.mapper.UserFavoriteMapper;
import com.msd.interaction.mapper.UserLikeMapper;
import com.msd.interaction.mapper.WatchHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评论计数异步化与评论请求幂等测试。
 */
class InteractionServiceAsyncTest {

    private UserLikeMapper userLikeMapper;
    private CommentMapper commentMapper;
    private OutboxEventMapper outboxEventMapper;
    private InteractionService service;

    @BeforeEach
    void setUp() {
        userLikeMapper = mock(UserLikeMapper.class);
        commentMapper = mock(CommentMapper.class);
        outboxEventMapper = mock(OutboxEventMapper.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
        service = new InteractionService(userLikeMapper, mock(UserFavoriteMapper.class),
                commentMapper, mock(WatchHistoryMapper.class), outboxEventMapper,
                mock(CommentCache.class), jsonMapper);
    }

    /**
     * 评论点赞只写事件，不在 interaction 本地同步加计数。
     */
    @Test
    void commentLikeDoesNotUpdateCountSynchronously() {
        when(commentMapper.selectById(8301L)).thenReturn(comment(8301L, 1));
        when(userLikeMapper.insertIgnore(1003L, 3, 8301L)).thenReturn(1);

        LikeResult result = service.like(1003L, 3, 8301L);

        assertThat(result.active()).isTrue();
        assertThat(result.eventId()).isNotBlank();
        verify(commentMapper, never()).incrementLikeCount(anyLong());
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(event.capture());
        assertThat(event.getValue().getUserId()).isEqualTo(1003L);
    }

    /**
     * 已删除/隐藏评论不能点赞。
     */
    @Test
    void rejectsLikeOnInvisibleComment() {
        when(commentMapper.selectById(8301L)).thenReturn(comment(8301L, 3));

        assertThatThrownBy(() -> service.like(1003L, 3, 8301L))
                .isInstanceOf(BizException.class);
        verify(userLikeMapper, never()).insertIgnore(anyLong(), any(), anyLong());
    }

    /**
     * clientRequestId 重复时直接返回已有评论，不重复写评论和事件。
     */
    @Test
    void duplicateClientRequestReturnsExistingComment() {
        Comment existing = comment(9100L, 1);
        when(commentMapper.selectByClientRequestId(1003L, "req-1")).thenReturn(existing);

        CommentResult result = service.createComment(1003L,
                new CommentRequest(5001L, 6001L, 0L, 0L, "好看", "req-1"));

        assertThat(result.commentId()).isEqualTo(9100L);
        assertThat(result.eventId()).isNull();
        verify(commentMapper, never()).insert(any(Comment.class));
        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
    }

    /**
     * 回复创建不在 interaction 本地同步加 root.reply_count，交给计数器消费者。
     */
    @Test
    void replyDoesNotUpdateRootCountSynchronously() {
        Comment root = comment(8301L, 1);
        when(commentMapper.selectById(8301L)).thenReturn(root);
        doAnswer(invocation -> {
            Comment inserted = invocation.getArgument(0);
            inserted.setId(9001L);
            return 1;
        }).when(commentMapper).insert(any(Comment.class));

        CommentResult result = service.createComment(1003L,
                new CommentRequest(5001L, 6001L, 8301L, 8301L, "回复内容", null));

        assertThat(result.commentId()).isEqualTo(9001L);
        assertThat(result.eventId()).isNotBlank();
        verify(commentMapper, never()).incrementReplyCount(anyLong());
    }

    /**
     * 构造评论行。
     */
    private Comment comment(Long id, int status) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setDramaId(5001L);
        comment.setEpisodeId(6001L);
        comment.setUserId(1002L);
        comment.setRootId(0L);
        comment.setParentId(0L);
        comment.setStatus(status);
        comment.setLikeCount(0L);
        comment.setReplyCount(0L);
        return comment;
    }
}
