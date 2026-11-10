package com.msd.drama.controller;

import com.msd.common.result.Result;
import com.msd.drama.dto.DramaStats;
import com.msd.drama.dto.EpisodeView;
import com.msd.drama.service.DramaQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 测试台使用的短剧只读接口。
 */
@RestController
@RequestMapping("/internal/dramas")
public class DramaQueryController {

    // 短剧与剧集只读查询服务
    private final DramaQueryService dramaQueryService;

    /**
     * 注入短剧查询服务。
     */
    public DramaQueryController(DramaQueryService dramaQueryService) {
        this.dramaQueryService = dramaQueryService;
    }

    /**
     * 查询短剧统计信息。
     */
    @GetMapping("/{dramaId}/stats")
    public Result<DramaStats> stats(@PathVariable("dramaId") Long dramaId) {
        // 1. 返回短剧基础信息和计数
        return Result.ok(dramaQueryService.getStats(dramaId));
    }

    /**
     * 查询短剧下的剧集列表和计数。
     */
    @GetMapping("/{dramaId}/episodes")
    public Result<List<EpisodeView>> episodes(@PathVariable("dramaId") Long dramaId) {
        // 1. 返回按集数升序的剧集列表
        return Result.ok(dramaQueryService.listEpisodes(dramaId));
    }
}
