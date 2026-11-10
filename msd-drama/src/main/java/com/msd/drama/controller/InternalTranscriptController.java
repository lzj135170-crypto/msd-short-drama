package com.msd.drama.controller;

import com.msd.common.result.Result;
import com.msd.drama.dto.AsrTranscriptRequest;
import com.msd.drama.dto.SubtitleImportResult;
import com.msd.drama.service.EpisodeTranscriptService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ASR worker 回传接口。
 *
 * <p>放在 /internal-source/** 下，网关不路由，只允许内网 ASR worker 直连。</p>
 */
@RestController
@RequestMapping("/internal-source/episodes")
public class InternalTranscriptController {

    // 字幕导入服务
    private final EpisodeTranscriptService transcriptService;

    /**
     * 注入字幕导入服务。
     */
    public InternalTranscriptController(EpisodeTranscriptService transcriptService) {
        this.transcriptService = transcriptService;
    }

    /**
     * 写入某集 ASR 字幕片段并触发索引。
     */
    @PostMapping("/{episodeId}/transcript")
    public Result<SubtitleImportResult> importAsr(
            @PathVariable("episodeId") Long episodeId,
            @Valid @RequestBody AsrTranscriptRequest request) {
        return Result.ok(transcriptService.importAsr(episodeId, request));
    }
}
