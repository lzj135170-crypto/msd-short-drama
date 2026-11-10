package com.msd.order.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.order.dto.PlaybackInfo;
import com.msd.order.service.PlaybackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 剧集播放授权接口。
 */
@RestController
@RequestMapping("/internal/playback")
public class PlaybackController {

    // 播放授权服务
    private final PlaybackService playbackService;

    /**
     * 注入播放授权服务。
     */
    public PlaybackController(PlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    /**
     * 查询播放信息；付费未解锁时 unlocked=false 且 videoUrl=null。
     */
    @GetMapping("/episodes/{episodeId}")
    public Result<PlaybackInfo> playback(
            @PathVariable("episodeId") Long episodeId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userIdHeader) {
        // 1. 解析网关注入的用户 ID
        Long userId = parseUserId(userIdHeader);
        // 2. 返回播放信息
        return Result.ok(playbackService.getPlayback(userId, episodeId));
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
