package com.msd.interaction.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.dto.PageQuery;
import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.interaction.dto.ActiveState;
import com.msd.interaction.dto.CommentView;
import com.msd.interaction.dto.DramaState;
import com.msd.interaction.dto.FavoriteItem;
import com.msd.interaction.dto.HistoryItem;
import com.msd.interaction.dto.InteractionStats;
import com.msd.interaction.dto.LikedDramaItem;
import com.msd.interaction.dto.MyCommentView;
import com.msd.interaction.dto.OutboxStatus;
import com.msd.interaction.service.InteractionFeedService;
import com.msd.interaction.service.InteractionQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 互动只读接口：outbox 状态、个人中心列表和状态查询。
 */
@RestController
@RequestMapping("/internal/interactions")
public class InteractionQueryController {

    // 互动只读查询服务：outbox 状态与点赞/收藏状态
    private final InteractionQueryService interactionQueryService;
    // 互动列表服务：评论、收藏、历史和个人中心计数
    private final InteractionFeedService interactionFeedService;

    /**
     * 注入互动查询和列表服务。
     */
    public InteractionQueryController(InteractionQueryService interactionQueryService,
                                      InteractionFeedService interactionFeedService) {
        this.interactionQueryService = interactionQueryService;
        this.interactionFeedService = interactionFeedService;
    }

    /**
     * 查询单条 outbox 事件状态。
     */
    @GetMapping("/outbox/{eventId}")
    public Result<OutboxStatus> outbox(@PathVariable("eventId") String eventId) {
        // 1. 返回事件投递状态
        return Result.ok(interactionQueryService.getOutboxStatus(eventId));
    }

    /**
     * 查询最近的 outbox 事件。
     */
    @GetMapping("/outbox")
    public Result<List<OutboxStatus>> recentOutbox(
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        // 1. 返回最近事件列表
        return Result.ok(interactionQueryService.recentOutbox(limit));
    }

    /**
     * 查询当前用户对目标的点赞状态。
     */
    @GetMapping("/likes/{targetType}/{targetId}")
    public Result<ActiveState> likeState(
            @PathVariable("targetType") Integer targetType,
            @PathVariable("targetId") Long targetId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并返回点赞状态
        Long userId = parseUserId(userIdHeader);
        return Result.ok(new ActiveState(
                interactionQueryService.isLiked(userId, targetType, targetId)));
    }

    /**
     * 查询当前用户对短剧的收藏状态。
     */
    @GetMapping("/favorites/{dramaId}")
    public Result<ActiveState> favoriteState(
            @PathVariable("dramaId") Long dramaId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并返回收藏状态
        Long userId = parseUserId(userIdHeader);
        return Result.ok(new ActiveState(
                interactionQueryService.isFavorited(userId, dramaId)));
    }

    /**
     * 查询当前用户对短剧的点赞和收藏状态。
     */
    @GetMapping("/dramas/{dramaId}/state")
    public Result<DramaState> dramaState(
            @PathVariable("dramaId") Long dramaId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并返回组合状态
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionFeedService.getDramaState(userId, dramaId));
    }

    /**
     * 分页查询根评论。
     */
    @GetMapping("/comments")
    public Result<PageResult<CommentView>> comments(
            @RequestParam("dramaId") Long dramaId,
            @RequestParam(value = "episodeId", required = false) Long episodeId,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户和分页参数
        Long userId = parseUserId(userIdHeader);
        PageQuery page = PageQuery.of(pageNo, pageSize);

        // 2. 返回根评论分页
        return Result.ok(interactionFeedService.listComments(userId, dramaId, episodeId, page));
    }

    /**
     * 分页查询某个根评论下的回复。
     */
    @GetMapping("/comments/{rootId}/replies")
    public Result<PageResult<CommentView>> replies(
            @PathVariable("rootId") Long rootId,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户和分页参数
        Long userId = parseUserId(userIdHeader);
        PageQuery page = PageQuery.of(pageNo, pageSize);

        // 2. 返回回复分页
        return Result.ok(interactionFeedService.listReplies(userId, rootId, page));
    }

    /**
     * 分页查询我的评论。
     */
    @GetMapping("/comments/mine")
    public Result<PageResult<MyCommentView>> myComments(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户和分页参数
        Long userId = parseUserId(userIdHeader);
        PageQuery page = PageQuery.of(pageNo, pageSize);

        // 2. 返回我的评论分页
        return Result.ok(interactionFeedService.listMyComments(userId, page));
    }

    /**
     * 分页查询我的收藏。
     */
    @GetMapping("/favorites")
    public Result<PageResult<FavoriteItem>> favorites(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户和分页参数
        Long userId = parseUserId(userIdHeader);
        PageQuery page = PageQuery.of(pageNo, pageSize);

        // 2. 返回收藏分页
        return Result.ok(interactionFeedService.listFavorites(userId, page));
    }

    /**
     * 分页查询观看历史。
     */
    @GetMapping("/history")
    public Result<PageResult<HistoryItem>> history(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户和分页参数
        Long userId = parseUserId(userIdHeader);
        PageQuery page = PageQuery.of(pageNo, pageSize);

        // 2. 返回历史分页
        return Result.ok(interactionFeedService.listHistory(userId, page));
    }

    /**
     * 首页继续观看：每部短剧取最近一条记录。
     */
    @GetMapping("/history/continue")
    public Result<List<HistoryItem>> continueWatching(
            @RequestParam(value = "limit", defaultValue = "3") Integer limit,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并返回最近观看记录
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionFeedService.listContinueWatching(userId, limit));
    }

    /**
     * 分页查询我点赞的短剧。
     */
    @GetMapping("/likes/dramas")
    public Result<PageResult<LikedDramaItem>> likedDramas(
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户和分页参数
        Long userId = parseUserId(userIdHeader);
        PageQuery page = PageQuery.of(pageNo, pageSize);

        // 2. 返回点赞短剧分页
        return Result.ok(interactionFeedService.listLikedDramas(userId, page));
    }

    /**
     * 查询个人中心互动计数。
     */
    @GetMapping("/stats")
    public Result<InteractionStats> stats(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析用户并返回四项计数
        Long userId = parseUserId(userIdHeader);
        return Result.ok(interactionFeedService.getStats(userId));
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
