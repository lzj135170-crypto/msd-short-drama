package com.msd.drama.controller;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.drama.dto.DramaReviewDecisionRequest;
import com.msd.drama.dto.DramaReviewState;
import com.msd.drama.entity.DramaReviewLog;
import com.msd.drama.service.DramaReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端短剧审核接口。
 *
 * <p>经网关访问 /api/drama/internal/admin/dramas；服务内再次校验 X-User-Role，
 * 直连端口只用于本地开发。</p>
 */
@RestController
@RequestMapping("/internal/admin/dramas")
public class AdminDramaReviewController {

    // 短剧审核服务
    private final DramaReviewService reviewService;

    /**
     * 注入短剧审核服务。
     */
    public AdminDramaReviewController(DramaReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 分页查询审核队列。
     */
    @GetMapping("/reviews")
    public Result<PageResult<DramaReviewCandidateDTO>> reviews(
            @RequestParam(value = "reviewStatus", required = false) Integer reviewStatus,
            @RequestParam(value = "aiReviewStatus", required = false) Integer aiReviewStatus,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 1. 审核队列只允许管理员查看
        RoleGuard.requireAdmin(role);
        return Result.ok(reviewService.listReviews(
                reviewStatus, aiReviewStatus, pageNo, pageSize));
    }

    /**
     * 查询某部短剧的审核操作日志。
     */
    @GetMapping("/{dramaId}/logs")
    public Result<List<DramaReviewLog>> logs(
            @PathVariable("dramaId") Long dramaId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 1. 只允许管理员查看审核日志
        return Result.ok(reviewService.listLogs(dramaId, role));
    }

    /**
     * 提交审核：创作者只能提交自己的短剧。
     */
    @PostMapping("/{dramaId}/submit")
    public Result<DramaReviewState> submit(
            @PathVariable("dramaId") Long dramaId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 解析用户并执行状态迁移
        return Result.ok(reviewService.submit(dramaId, RoleGuard.requireUserId(userId), role));
    }

    /**
     * 人工审核通过或驳回。
     */
    @PostMapping("/{dramaId}/review")
    public Result<DramaReviewState> review(
            @PathVariable("dramaId") Long dramaId,
            @Valid @RequestBody DramaReviewDecisionRequest request,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 管理员决策，服务内再次校验角色
        return Result.ok(reviewService.review(
                dramaId, RoleGuard.requireUserId(userId), role, request));
    }

    /**
     * 下架已上架短剧。
     */
    @PostMapping("/{dramaId}/offline")
    public Result<DramaReviewState> offline(
            @PathVariable("dramaId") Long dramaId,
            @RequestParam(value = "remark", required = false) String remark,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 管理员下架
        return Result.ok(reviewService.offline(
                dramaId, RoleGuard.requireUserId(userId), role, remark));
    }
}
