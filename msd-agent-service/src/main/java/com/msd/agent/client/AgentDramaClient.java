package com.msd.agent.client;

import com.msd.agent.client.dto.AgentDramaViews;
import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * 短剧服务只读 Feign 客户端，供 Agent 工具查询首页、详情和剧集。
 */
@FeignClient(name = ServiceNames.DRAMA, path = "/internal", configuration = AgentFeignConfig.class)
public interface AgentDramaClient {

    /**
     * 查询首页轮播、推荐区块和分类。
     */
    @GetMapping("/home")
    Result<AgentDramaViews.HomeFeedView> home();

    /**
     * 查询短剧详情。
     */
    @GetMapping("/dramas/{dramaId}")
    Result<AgentDramaViews.DramaDetailView> detail(@PathVariable("dramaId") Long dramaId);

    /**
     * 查询短剧剧集列表。
     */
    @GetMapping("/dramas/{dramaId}/episodes")
    Result<List<AgentDramaViews.EpisodeView>> episodes(@PathVariable("dramaId") Long dramaId);
}
