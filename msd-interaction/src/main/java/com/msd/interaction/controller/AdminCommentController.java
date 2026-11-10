package com.msd.interaction.controller;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.interaction.dto.AdminCommentItem;
import com.msd.interaction.service.CommentAiReviewService;
import com.msd.interaction.service.InteractionAdminService;
import com.msd.interaction.service.InteractionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端评论治理接口。
 *
 * <p>经网关访问 /api/interaction/internal/admin/comments；只允许 ADMIN。</p>
 */
@RestController
@RequestMapping("/internal/admin/comments")
public class AdminCommentController {

    // 评论列表服务
    private final InteractionAdminService adminService;
    // 评论写服务
    private final InteractionService interactionService;
    // 评论 AI 预审服务
    private final CommentAiReviewService aiReviewService;

    /**
     * 注入评论列表、写服务和 AI 预审服务。
     */
    public AdminCommentController(InteractionAdminService adminService,
                                  InteractionService interactionService,
                                  CommentAiReviewService aiReviewService) {
        this.adminService = adminService;
        this.interactionService = interactionService;
        this.aiReviewService = aiReviewService;
    }

    /**
     * 分页查询评论。
     */
    @GetMapping
    public Result<PageResult<AdminCommentItem>> list(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "dramaId", required = false) Long dramaId,
            @RequestParam(value = "aiReviewStatus", required = false) Integer aiReviewStatus,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 1. 只允许管理员查看
        RoleGuard.requireAdmin(role);
        return Result.ok(adminService.listComments(
                status, dramaId, aiReviewStatus, pageNo, pageSize));
    }

    /**
     * 隐藏评论，根评论级联隐藏回复。
     */
    @PostMapping("/{commentId}/hide")
    public Result<Boolean> hide(
            @PathVariable("commentId") Long commentId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 1. 只允许管理员操作
        RoleGuard.requireAdmin(role);
        return Result.ok(interactionService.adminHideComment(commentId));
    }

    /**
     * 重新执行评论 AI 预审；只写 AI 列，不改人工状态。
     */
    @PostMapping("/{commentId}/ai-audit")
    public Result<Boolean> aiAudit(
            @PathVariable("commentId") Long commentId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 1. 只允许管理员触发
        RoleGuard.requireAdmin(role);
        // 2. 异步执行，接口立即返回
        aiReviewService.reviewAsync(commentId);
        return Result.ok(true);
    }

    /**
     * 删除评论，根评论级联删除回复。
     */
    @PostMapping("/{commentId}/delete")
    public Result<Boolean> delete(
            @PathVariable("commentId") Long commentId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 1. 只允许管理员操作
        RoleGuard.requireAdmin(role);
        return Result.ok(interactionService.adminDeleteComment(commentId));
    }
}
