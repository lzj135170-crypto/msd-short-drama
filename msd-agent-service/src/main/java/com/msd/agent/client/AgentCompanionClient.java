package com.msd.agent.client;

import com.msd.agent.client.dto.AgentCompanionViews.CompanionContextRequest;
import com.msd.agent.client.dto.AgentCompanionViews.CompanionContextView;
import com.msd.agent.client.dto.AgentCompanionViews.EpisodeSummaryView;
import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 内容服务伴看工具接口，只走服务间直连。
 */
@FeignClient(name = ServiceNames.CONTENT, contextId = "agentCompanionClient",
        path = "/internal/ai/companion/tools", configuration = AgentFeignConfig.class)
public interface AgentCompanionClient {

    /**
     * 查询当前集摘要。
     */
    @GetMapping("/episodes/{episodeId}/summary")
    Result<EpisodeSummaryView> summary(@PathVariable("episodeId") Long episodeId);

    /**
     * 检索当前集之前的前情。
     */
    @PostMapping("/context")
    Result<CompanionContextView> context(@RequestBody CompanionContextRequest request);
}
