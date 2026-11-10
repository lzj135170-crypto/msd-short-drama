package com.msd.content.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import com.msd.content.dto.RecommendationExposureRequest;
import com.msd.content.dto.RecommendationExposureResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 推荐曝光上报客户端，直连 interaction 内部接口。
 */
@FeignClient(name = ServiceNames.INTERACTION, contextId = "recommendationExposureClient",
        path = "/internal-source/recommendations", configuration = ContentFeignConfig.class)
public interface RecommendationExposureClient {

    /**
     * 幂等上报一批曝光。
     */
    @PostMapping("/exposures")
    Result<RecommendationExposureResult> record(
            @RequestBody RecommendationExposureRequest request);
}
