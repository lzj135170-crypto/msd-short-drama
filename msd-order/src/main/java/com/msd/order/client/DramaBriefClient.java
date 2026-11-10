package com.msd.order.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.dto.DramaBriefDTO;
import com.msd.common.dto.IdBatchRequest;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 短剧简要信息 Feign 客户端，供已解锁列表补齐短剧标题。
 */
@FeignClient(name = ServiceNames.DRAMA, contextId = "dramaBriefClient",
        path = "/internal/dramas", configuration = FeignConfig.class)
public interface DramaBriefClient {

    /**
     * 批量查询已上架短剧简要信息。
     */
    @PostMapping("/batch")
    Result<List<DramaBriefDTO>> batch(@RequestBody IdBatchRequest request);
}
