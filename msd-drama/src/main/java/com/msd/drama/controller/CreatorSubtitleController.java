package com.msd.drama.controller;

import com.msd.common.auth.RoleGuard;
import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import com.msd.drama.dto.SubtitleImportRequest;
import com.msd.drama.dto.SubtitleImportResult;
import com.msd.drama.service.EpisodeTranscriptService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 创作者字幕导入接口。
 *
 * <p>Demo 直接提交 SRT/VTT 文本；服务内校验短剧归属和剧集归属。</p>
 */
@RestController
@RequestMapping("/internal/creator/dramas")
public class CreatorSubtitleController {

    // 字幕导入服务
    private final EpisodeTranscriptService transcriptService;

    /**
     * 注入字幕导入服务。
     */
    public CreatorSubtitleController(EpisodeTranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    /**
     * 导入或覆盖某集字幕。
     */
    @PostMapping("/{dramaId}/episodes/{episodeId}/subtitles")
    public Result<SubtitleImportResult> importSubtitle(
            @PathVariable("dramaId") Long dramaId,
            @PathVariable("episodeId") Long episodeId,
            @Valid @RequestBody SubtitleImportRequest request,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId) {
        // 1. 创作者只能导入自己短剧的字幕，管理员不限
        return Result.ok(transcriptService.importSubtitle(
                dramaId, episodeId, RoleGuard.requireUserId(userId), role, request));
    }
}
