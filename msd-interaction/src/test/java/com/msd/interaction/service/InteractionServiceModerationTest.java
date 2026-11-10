package com.msd.interaction.service;

import com.msd.interaction.entity.Comment;
import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.mapper.CommentMapper;
import com.msd.interaction.mapper.OutboxEventMapper;
import com.msd.interaction.mapper.UserFavoriteMapper;
import com.msd.interaction.mapper.UserLikeMapper;
import com.msd.interaction.mapper.WatchHistoryMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员隐藏/删除评论的计数和 outbox 事件测试。
 */
class InteractionServiceModerationTest {

    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final OutboxEventMapper outboxEventMapper = mock(OutboxEventMapper.class);

    private final InteractionService service = new InteractionService(
            mock(UserLikeMapper.class),
            mock(UserFavoriteMapper.class),
            commentMapper,
            mock(WatchHistoryMapper.class),
            outboxEventMapper,
            mock(CommentCache.class),
            JsonMapper.shared());

    /**
     * 隐藏根评论时级联隐藏回复，并分别为原本可见的评论写删除事件。
     */
    @Test
    void hidesRootAndWritesCascadeEvents() {
        Comment root = comment(8335L, 1003L, 0L, 1);
        Comment reply = comment(8336L, 1004L, 8335L, 1);
        when(commentMapper.selectById(8335L)).thenReturn(root);
        when(commentMapper.selectActiveReplies(8335L)).thenReturn(List.of(reply));
        when(commentMapper.adminHide(8335L)).thenReturn(1);
        when(commentMapper.adminHide(8336L)).thenReturn(1);

        service.adminHideComment(8335L);

        verify(outboxEventMapper, times(2)).insert(any(OutboxEvent.class));
        verify(commentMapper, never()).decrementReplyCount(any());
    }

    /**
     * 隐藏回复时减少根评论回复数，并只写一条事件。
     */
    @Test
    void hidesReplyWithoutSynchronousCounter() {
        when(commentMapper.selectById(8336L)).thenReturn(comment(8336L, 1004L, 8335L, 1));
        when(commentMapper.adminHide(8336L)).thenReturn(1);

        service.adminHideComment(8336L);

        verify(commentMapper, never()).decrementReplyCount(8335L);
        verify(outboxEventMapper, times(1)).insert(any(OutboxEvent.class));
    }

    /**
     * 已隐藏评论删除时不再重复扣减评论数。
     */
    @Test
    void deletingHiddenCommentDoesNotEmitEvent() {
        when(commentMapper.selectById(8336L)).thenReturn(comment(8336L, 1004L, 8335L, 2));
        when(commentMapper.adminDeleteById(8336L)).thenReturn(1);

        service.adminDeleteComment(8336L);

        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
        verify(commentMapper, never()).decrementReplyCount(any());
    }

    /**
     * 构造评论。
     */
    private Comment comment(Long id, Long userId, Long rootId, Integer status) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setDramaId(5001L);
        comment.setEpisodeId(6001L);
        comment.setUserId(userId);
        comment.setRootId(rootId);
        comment.setParentId(rootId);
        comment.setContent("测试评论");
        comment.setLikeCount(0L);
        comment.setReplyCount(0L);
        comment.setStatus(status);
        return comment;
    }
}
