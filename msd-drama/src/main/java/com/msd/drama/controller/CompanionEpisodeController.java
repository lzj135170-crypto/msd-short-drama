package com.msd.drama.controller;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.dto.SummaryClaimDTO;
import com.msd.common.dto.SummaryWritebackRequest;
import com.msd.common.result.Result;
import com.msd.drama.service.CompanionEpisodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 伴看剧集元信息与摘要状态内部接口。
 *
 * <p>放在 /internal-source/** 下，网关不路由，只允许 content-service 直连。</p>
 */
@RestController
@RequestMapping("/internal-source/episodes")
public class CompanionEpisodeController {

    // AI 伴看剧集服务
    private final CompanionEpisodeService companionEpisodeService;

    /**
     * 注入 AI 伴看剧集服务。
     */
    public CompanionEpisodeController(CompanionEpisodeService companionEpisodeService) {
        this.companionEpisodeService = companionEpisodeService;
    }

    /**
     * 查询剧集元信息和当前摘要；不可见时返回 data=null。
     */
    @GetMapping("/{episodeId}/companion")
    public Result<CompanionEpisodeDTO> companion(@PathVariable("episodeId") Long episodeId) {
        // 1. 只返回在线剧集且所属短剧已上架的数据
        return Result.ok(companionEpisodeService.getCompanionEpisode(episodeId));
    }

    /**
     * 查询可批量生成摘要的在线剧集 ID，供 content-service 预热使用。
     */
    @GetMapping("/summary-candidates")
    public Result<List<Long>> summaryCandidates(
            @RequestParam("dramaId") Long dramaId,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit) {
        // 1. 只返回摘要缺失或失败可重试的在线剧集
        return Result.ok(companionEpisodeService.listSummaryCandidates(dramaId, limit));
    }

    /**
     * 认领摘要生成权；不可见时返回 data=null。
     */
    @PostMapping("/{episodeId}/summary/claim")
    public Result<SummaryClaimDTO> claim(@PathVariable("episodeId") Long episodeId) {
        // 1. 返回是否认领成功以及当前摘要状态
        return Result.ok(companionEpisodeService.claimSummary(episodeId));
    }

    /**
     * 回写生成的摘要；已有摘要时不覆盖。
     */
    @PutMapping("/{episodeId}/summary")
    public Result<Boolean> writeback(@PathVariable("episodeId") Long episodeId,
                                     @Valid @RequestBody SummaryWritebackRequest request) {
        // 1. 条件写入，返回是否真正落库
        return Result.ok(companionEpisodeService.writebackSummary(episodeId, request));
    }

    /**
     * 释放摘要认领并标记失败，允许后续重试。
     */
    @PostMapping("/{episodeId}/summary/release")
    public Result<Boolean> release(@PathVariable("episodeId") Long episodeId) {
        // 1. 只释放仍处于生成中的记录
        return Result.ok(companionEpisodeService.releaseSummaryClaim(episodeId));
    }
}
