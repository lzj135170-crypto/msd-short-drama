package com.msd.content.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeTranscriptDTO;
import com.msd.common.dto.RecapEpisodeDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 短剧目录导出客户端，只走服务间直连，不经过网关。
 */
@FeignClient(name = ServiceNames.DRAMA, contextId = "dramaCatalogClient",
        path = "/internal-source/rag", configuration = ContentFeignConfig.class)
public interface DramaCatalogClient {

    /**
     * 分页导出一页已上架短剧。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 分页封装的短剧导出数据
     */
    @GetMapping("/dramas")
    Result<PageResult<DramaRagExportDTO>> export(@RequestParam("page") int page,
                                                 @RequestParam("size") int size);

    /**
     * 查询单部已上架短剧的导出数据。
     */
    @GetMapping("/dramas/{dramaId}")
    Result<DramaRagExportDTO> detail(@PathVariable("dramaId") Long dramaId);

    /**
     * 查询相似短剧的规则兜底候选。
     */
    @GetMapping("/dramas/{dramaId}/related")
    Result<List<DramaRagExportDTO>> relatedFallback(@PathVariable("dramaId") Long dramaId,
                                                    @RequestParam("limit") int limit);

    /**
     * 查询当前集及之前的在线分集摘要。
     */
    @GetMapping("/dramas/{dramaId}/recap")
    Result<List<RecapEpisodeDTO>> recap(@PathVariable("dramaId") Long dramaId,
                                        @RequestParam("episodeNo") Integer episodeNo);

    /**
     * 查询单集字幕片段，供 transcript 索引。
     */
    @GetMapping("/episodes/{episodeId}/transcript")
    Result<EpisodeTranscriptDTO> transcript(@PathVariable("episodeId") Long episodeId);

    /**
     * 分页查询有字幕的在线剧集 ID，供全量重建字幕索引。
     */
    @GetMapping("/transcripts")
    Result<PageResult<Long>> transcriptEpisodes(@RequestParam("page") int page,
                                                @RequestParam("size") int size);
}
