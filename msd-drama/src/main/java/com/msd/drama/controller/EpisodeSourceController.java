package com.msd.drama.controller;

import com.msd.common.result.Result;
import com.msd.drama.dto.EpisodeSource;
import com.msd.drama.service.EpisodeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部剧集播放源接口，只允许服务间直连。
 *
 * <p>网关只路由 /api/drama/internal/**，因此 /internal-source/** 不会暴露给客户端。</p>
 */
@RestController
@RequestMapping("/internal-source/episodes")
public class EpisodeSourceController {

    // 剧集查询与播放源服务
    private final EpisodeService episodeService;

    /**
     * 注入剧集服务。
     */
    public EpisodeSourceController(EpisodeService episodeService) {
        this.episodeService = episodeService;
    }

    /**
     * 查询可播放的剧集源；不可见时返回 data=null。
     */
    @GetMapping("/{episodeId}")
    public Result<EpisodeSource> source(@PathVariable("episodeId") Long episodeId) {
        // 1. 只返回在线剧集且所属短剧已上架的播放源
        return Result.ok(episodeService.getSource(episodeId));
    }
}
