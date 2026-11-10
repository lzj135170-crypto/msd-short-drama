package com.msd.order.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import com.msd.order.dto.EpisodeSourceDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * drama 内部播放源 Feign 客户端。
 *
 * <p>路径 /internal-source/** 不在网关路由范围内，只能服务间直连。</p>
 */
@FeignClient(name = ServiceNames.DRAMA, contextId = "dramaSourceClient",
        path = "/internal-source/episodes", configuration = FeignConfig.class)
public interface DramaSourceClient {

    /**
     * 查询可播放的剧集源，不可见时返回 data=null。
     */
    @GetMapping("/{episodeId}")
    Result<EpisodeSourceDTO> getSource(@PathVariable("episodeId") Long episodeId);
}
