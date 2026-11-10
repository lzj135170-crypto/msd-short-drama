package com.msd.content.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.dto.SummaryClaimDTO;
import com.msd.common.dto.SummaryWritebackRequest;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 短剧服务 AI 伴看内部接口，只走服务间直连。
 */
@FeignClient(name = ServiceNames.DRAMA, contextId = "dramaCompanionClient",
        path = "/internal-source/episodes", configuration = ContentFeignConfig.class)
public interface DramaCompanionClient {

    /**
     * 查询剧集元信息和当前摘要；剧集不可见时 data=null。
     */
    @GetMapping("/{episodeId}/companion")
    Result<CompanionEpisodeDTO> companion(@PathVariable("episodeId") Long episodeId);

    /**
     * 查询某部短剧下可生成摘要的在线剧集 ID。
     */
    @GetMapping("/summary-candidates")
    Result<List<Long>> summaryCandidates(@RequestParam("dramaId") Long dramaId,
                                         @RequestParam("limit") int limit);

    /**
     * 认领摘要生成权。
     */
    @PostMapping("/{episodeId}/summary/claim")
    Result<SummaryClaimDTO> claimSummary(@PathVariable("episodeId") Long episodeId);

    /**
     * 回写生成的摘要；已有摘要时不覆盖。
     */
    @PutMapping("/{episodeId}/summary")
    Result<Boolean> writebackSummary(@PathVariable("episodeId") Long episodeId,
                                     @RequestBody SummaryWritebackRequest request);

    /**
     * 释放摘要认领并标记失败。
     */
    @PostMapping("/{episodeId}/summary/release")
    Result<Boolean> releaseSummaryClaim(@PathVariable("episodeId") Long episodeId);
}
