package com.msd.interaction.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.dto.DramaBriefDTO;
import com.msd.common.dto.EpisodeBriefDTO;
import com.msd.common.dto.IdBatchRequest;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 短剧服务 Feign 客户端，用于收藏/历史/点赞/我的评论的批量补齐。
 */
@FeignClient(name = ServiceNames.DRAMA, contextId = "interactionDramaClient",
        path = "/internal", configuration = FeignConfig.class)
public interface DramaClient {

    /**
     * 批量查询已上架短剧摘要。
     */
    @PostMapping("/dramas/batch")
    Result<List<DramaBriefDTO>> getDramas(@RequestBody IdBatchRequest request);

    /**
     * 批量查询剧集摘要，包含下线剧集。
     */
    @PostMapping("/episodes/batch")
    Result<List<EpisodeBriefDTO>> getEpisodes(@RequestBody IdBatchRequest request);
}
