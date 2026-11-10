package com.msd.drama.controller;

import com.msd.common.dto.EpisodeBriefDTO;
import com.msd.common.dto.IdBatchRequest;
import com.msd.common.result.Result;
import com.msd.drama.dto.EpisodeInfo;
import com.msd.drama.dto.UnlockRefRequest;
import com.msd.drama.service.EpisodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部剧集接口，供 order 解锁流程调用。
 */
@RestController
@RequestMapping("/internal/episodes")
public class EpisodeController {

    // 剧集查询与解锁引用服务
    private final EpisodeService episodeService;

    /**
     * 注入剧集服务。
     */
    public EpisodeController(EpisodeService episodeService) {
        this.episodeService = episodeService;
    }

    /**
     * 查询剧集价格、状态和解锁次数。
     */
    @GetMapping("/{episodeId}")
    public Result<EpisodeInfo> getEpisode(@PathVariable("episodeId") Long episodeId) {
        // 1. 查询并返回剧集信息
        return Result.ok(episodeService.getEpisode(episodeId));
    }

    /**
     * 批量查询剧集摘要，供互动列表补齐标题和封面。
     */
    @PostMapping("/batch")
    public Result<List<EpisodeBriefDTO>> batch(@Valid @RequestBody IdBatchRequest request) {
        // 1. 返回存在的剧集，包含下线状态
        return Result.ok(episodeService.getBriefs(request.ids()));
    }

    /**
     * 写入解锁引用并增加解锁计数；重复调用幂等。
     */
    @PostMapping("/{episodeId}/unlock-ref")
    public Result<Boolean> addUnlockRef(@PathVariable("episodeId") Long episodeId,
                                        @Valid @RequestBody UnlockRefRequest request) {
        // 1. 首次写入返回 true，重复调用返回 false
        return Result.ok(episodeService.addUnlockRef(episodeId, request));
    }
}
