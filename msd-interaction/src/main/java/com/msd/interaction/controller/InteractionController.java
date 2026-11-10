package com.msd.interaction.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.interaction.dto.CommentRequest;
import com.msd.interaction.dto.CommentResult;
import com.msd.interaction.dto.FavoriteRequest;
import com.msd.interaction.dto.FavoriteResult;
import com.msd.interaction.dto.HistoryRequest;
import com.msd.interaction.dto.HistoryResult;
import com.msd.interaction.dto.LikeRequest;
import com.msd.interaction.dto.LikeResult;
import com.msd.interaction.service.InteractionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动写接口，用户 ID 由网关注入。
 */
@RestController
@RequestMapping("/internal/interactions")
public class InteractionController {

    // 互动写服务
    private final InteractionService interactionService;

    /**
     * 注入互动服务。
     */
    public InteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /**
     * 点赞。
     */
    @PostMapping("/likes")
    public Result<LikeResult> like(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @Valid @RequestBody LikeRequest request) {
        // 1. 解析用户并执行点赞
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionService.like(userId, request.targetType(), request.targetId()));
    }

    /**
     * 取消点赞。
     */
    @DeleteMapping("/likes/{targetType}/{targetId}")
    public Result<LikeResult> unlike(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @PathVariable("targetType") Integer targetType,
            @PathVariable("targetId") Long targetId) {
        // 1. 解析用户并取消点赞
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionService.unlike(userId, targetType, targetId));
    }

    /**
     * 收藏。
     */
    @PostMapping("/favorites")
    public Result<FavoriteResult> favorite(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @Valid @RequestBody FavoriteRequest request) {
        // 1. 解析用户并收藏
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionService.favorite(userId, request.dramaId()));
    }

    /**
     * 取消收藏。
     */
    @DeleteMapping("/favorites/{dramaId}")
    public Result<FavoriteResult> unfavorite(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @PathVariable("dramaId") Long dramaId) {
        // 1. 解析用户并取消收藏
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionService.unfavorite(userId, dramaId));
    }

    /**
     * 创建评论或回复。
     */
    @PostMapping("/comments")
    public Result<CommentResult> comment(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @Valid @RequestBody CommentRequest request) {
        // 1. 解析用户并创建评论
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionService.createComment(userId, request));
    }

    /**
     * 删除自己的评论。
     */
    @DeleteMapping("/comments/{commentId}")
    public Result<Boolean> deleteComment(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @PathVariable("commentId") Long commentId) {
        // 1. 解析用户并逻辑删除评论
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionService.deleteComment(userId, commentId));
    }

    /**
     * 上报观看进度。
     */
    @PostMapping("/history")
    public Result<HistoryResult> history(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @Valid @RequestBody HistoryRequest request) {
        // 1. 解析用户并 upsert 观看历史
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionService.recordHistory(userId, request));
    }

    /**
     * 删除单条观看历史。
     */
    @DeleteMapping("/history/{episodeId}")
    public Result<Boolean> deleteHistory(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader,
            @PathVariable("episodeId") Long episodeId) {
        // 1. 解析用户并删除指定剧集历史
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionService.deleteHistory(userId, episodeId));
    }

    /**
     * 清空全部观看历史。
     */
    @DeleteMapping("/history")
    public Result<Integer> clearHistory(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并清空历史，返回删除条数
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionService.clearHistory(userId));
    }

    /**
     * 解析 X-User-Id，缺失或非法时按未登录处理。
     */
    private Long parseUserId(String userIdHeader) {
        // 1. 网关未注入用户 ID
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        // 2. 非数字同样视为未登录
        try {
            return Long.parseLong(userIdHeader);
        } catch (NumberFormatException ex) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }
}
