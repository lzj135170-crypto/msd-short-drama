package com.msd.order.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.dto.EpisodeBriefDTO;
import com.msd.common.dto.IdBatchRequest;
import com.msd.common.result.Result;
import com.msd.order.dto.EpisodeInfoDTO;
import com.msd.order.dto.UnlockRefRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 短剧服务 Feign 客户端。
 */
@FeignClient(name = ServiceNames.DRAMA, path = "/internal/episodes",
        configuration = FeignConfig.class)
public interface DramaClient {

    /**
     * 查询剧集价格和上线状态。
     */
    @GetMapping("/{episodeId}")
    Result<EpisodeInfoDTO> getEpisode(@PathVariable("episodeId") Long episodeId);

    /**
     * 写入 drama 侧解锁引用并增加解锁计数。
     */
    @PostMapping("/{episodeId}/unlock-ref")
    Result<Boolean> addUnlockRef(@PathVariable("episodeId") Long episodeId,
                                 @RequestBody UnlockRefRequest request);

    /**
     * 批量查询剧集标题和封面，供已解锁列表补齐。
     */
    @PostMapping("/batch")
    Result<List<EpisodeBriefDTO>> batchEpisodes(@RequestBody IdBatchRequest request);
}
