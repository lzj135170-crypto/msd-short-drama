package com.msd.drama.controller;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.drama.dto.CreatorDramaDetail;
import com.msd.drama.dto.DramaReviewState;
import com.msd.drama.dto.DramaWriteRequest;
import com.msd.drama.service.DramaWriteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 创作者短剧工作台接口。
 *
 * <p>经网关访问 /api/drama/internal/creator/dramas；服务内校验角色和作者归属。</p>
 */
@RestController
@RequestMapping("/internal/creator/dramas")
public class CreatorDramaController {

    // 创作者写服务
    private final DramaWriteService writeService;

    /**
     * 注入创作者写服务。
     */
    public CreatorDramaController(DramaWriteService writeService) {
        this.writeService = writeService;
    }

    /**
     * 分页查询我的短剧。
     */
    @GetMapping
    public Result<PageResult<DramaReviewCandidateDTO>> list(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 只返回当前用户自己的短剧
        return Result.ok(writeService.listMine(
                RoleGuard.requireUserId(userId), role, pageNo, pageSize));
    }

    /**
     * 查询短剧详情和剧集，供编辑页回填。
     */
    @GetMapping("/{dramaId}")
    public Result<CreatorDramaDetail> detail(
            @PathVariable("dramaId") Long dramaId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 创作者只能查看自己的短剧，管理员不限
        return Result.ok(writeService.getMine(
                dramaId, RoleGuard.requireUserId(userId), role));
    }

    /**
     * 创建草稿短剧和剧集。
     */
    @PostMapping
    public Result<CreatorDramaDetail> create(
            @Valid @RequestBody DramaWriteRequest request,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 作者固定为当前用户
        return Result.ok(writeService.create(
                RoleGuard.requireUserId(userId), role, request));
    }

    /**
     * 编辑草稿或驳回的短剧。
     */
    @PutMapping("/{dramaId}")
    public Result<CreatorDramaDetail> update(
            @PathVariable("dramaId") Long dramaId,
            @Valid @RequestBody DramaWriteRequest request,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 服务内校验归属和审核状态
        return Result.ok(writeService.update(
                dramaId, RoleGuard.requireUserId(userId), role, request));
    }

    /**
     * 删除草稿或驳回的短剧。
     */
    @DeleteMapping("/{dramaId}")
    public Result<Boolean> delete(
            @PathVariable("dramaId") Long dramaId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 软删除，已上架内容返回冲突
        return Result.ok(writeService.delete(
                dramaId, RoleGuard.requireUserId(userId), role));
    }

    /**
     * 提交审核。
     */
    @PostMapping("/{dramaId}/submit")
    public Result<DramaReviewState> submit(
            @PathVariable("dramaId") Long dramaId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 复用审核状态机
        return Result.ok(writeService.submit(
                dramaId, RoleGuard.requireUserId(userId), role));
    }
}
