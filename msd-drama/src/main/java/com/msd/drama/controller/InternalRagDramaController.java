package com.msd.drama.controller;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeTranscriptDTO;
import com.msd.common.dto.RecapEpisodeDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.drama.service.DramaRagExportService;
import com.msd.drama.service.EpisodeTranscriptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 索引目录导出接口。
 *
 * <p>放在 /internal-source/** 下，网关不路由，只允许服务间直连。</p>
 */
@RestController
@RequestMapping("/internal-source/rag")
public class InternalRagDramaController {

    // RAG 索引目录导出服务
    private final DramaRagExportService exportService;
    // 分集字幕导出服务
    private final EpisodeTranscriptService transcriptService;

    /**
     * 注入 RAG 目录导出服务和字幕导出服务。
     */
    public InternalRagDramaController(DramaRagExportService exportService,
                                      EpisodeTranscriptService transcriptService) {
        this.exportService = exportService;
        this.transcriptService = transcriptService;
    }

    /**
     * 分页导出已上架短剧。
     */
    @GetMapping("/dramas")
    public Result<PageResult<DramaRagExportDTO>> dramas(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "200") int size) {
        // 1. 返回已上架短剧、分类、标签、演员和在线剧集标题
        return Result.ok(exportService.export(page, size));
    }

    /**
     * 查询单部已上架短剧的导出数据，供相似推荐取源向量文本。
     */
    @GetMapping("/dramas/{dramaId}")
    public Result<DramaRagExportDTO> drama(@PathVariable("dramaId") Long dramaId) {
        // 1. 短剧不可见时 data=null
        return Result.ok(exportService.exportOne(dramaId));
    }

    /**
     * 查询相似短剧的规则兜底候选。
     */
    @GetMapping("/dramas/{dramaId}/related")
    public Result<List<DramaRagExportDTO>> related(
            @PathVariable("dramaId") Long dramaId,
            @RequestParam(value = "limit", defaultValue = "6") int limit) {
        // 1. 同分类、共同标签优先，再按热度排序
        return Result.ok(exportService.relatedFallback(dramaId, limit));
    }

    /**
     * 查询当前集及之前的在线分集摘要。
     */
    @GetMapping("/dramas/{dramaId}/recap")
    public Result<List<RecapEpisodeDTO>> recap(
            @PathVariable("dramaId") Long dramaId,
            @RequestParam("episodeNo") Integer episodeNo) {
        // 1. 只返回当前集及之前，未来集由 SQL 过滤
        return Result.ok(exportService.recap(dramaId, episodeNo));
    }

    /**
     * 导出单集字幕片段，供 content 建立 transcript 索引。
     */
    @GetMapping("/episodes/{episodeId}/transcript")
    public Result<EpisodeTranscriptDTO> transcript(
            @PathVariable("episodeId") Long episodeId) {
        // 1. 不可见剧集或无字幕时 data=null
        return Result.ok(transcriptService.export(episodeId));
    }

    /**
     * 分页查询有字幕的在线剧集 ID，供全量重建字幕索引。
     */
    @GetMapping("/transcripts")
    public Result<PageResult<Long>> transcriptEpisodes(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "200") Integer size) {
        return Result.ok(transcriptService.listTranscriptEpisodeIds(page, size));
    }
}
