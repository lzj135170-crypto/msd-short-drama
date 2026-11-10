package com.msd.interaction.service;

import com.msd.common.dto.PageQuery;
import com.msd.common.result.PageResult;
import com.msd.interaction.dto.AdminCommentItem;
import com.msd.interaction.entity.Comment;
import com.msd.interaction.mapper.CommentMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理端评论列表查询。
 */
@Service
public class InteractionAdminService {

    // 评论 Mapper
    private final CommentMapper commentMapper;

    /**
     * 注入评论 Mapper。
     */
    public InteractionAdminService(CommentMapper commentMapper) {
        this.commentMapper = commentMapper;
    }

    /**
     * 分页查询评论，可按状态和短剧过滤。
     */
    public PageResult<AdminCommentItem> listComments(Integer status,
                                                     Long dramaId,
                                                     Integer aiReviewStatus,
                                                     Integer pageNo,
                                                     Integer pageSize) {
        // 1. 归一化分页参数
        PageQuery page = PageQuery.of(pageNo, pageSize);
        long total = commentMapper.countAdminPage(status, dramaId, aiReviewStatus);
        if (total <= 0) {
            return PageResult.empty(page.pageNo(), page.pageSize());
        }
        // 2. 查询当前页并转换为管理端视图
        List<AdminCommentItem> records = commentMapper.selectAdminPage(
                        status, dramaId, aiReviewStatus, page.offset(), page.pageSize())
                .stream()
                .map(this::toItem)
                .toList();
        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 评论实体转管理端视图。
     */
    private AdminCommentItem toItem(Comment comment) {
        return new AdminCommentItem(
                comment.getId(),
                comment.getDramaId(),
                comment.getEpisodeId(),
                comment.getUserId(),
                comment.getRootId(),
                comment.getParentId(),
                comment.getContent(),
                comment.getLikeCount(),
                comment.getReplyCount(),
                comment.getStatus(),
                comment.getAiReviewStatus(),
                comment.getAiReviewResult(),
                comment.getAiReviewTime(),
                comment.getCreateTime());
    }
}
