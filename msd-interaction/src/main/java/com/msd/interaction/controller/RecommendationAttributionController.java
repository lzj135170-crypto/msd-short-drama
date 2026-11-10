package com.msd.interaction.controller;

import com.msd.common.result.Result;
import com.msd.interaction.dto.RecommendationExposureRequest;
import com.msd.interaction.dto.RecommendationExposureResult;
import com.msd.interaction.service.RecommendationAttributionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐曝光内部接口，只允许 content-service Feign 直连。
 *
 * <p>放在 /internal-source/** 下，网关不路由。</p>
 */
@RestController
@RequestMapping("/internal-source/recommendations")
public class RecommendationAttributionController {

    // 推荐归因服务
    private final RecommendationAttributionService attributionService;

    /**
     * 注入归因服务。
     */
    public RecommendationAttributionController(
            RecommendationAttributionService attributionService) {
        this.attributionService = attributionService;
    }

    /**
     * 批量写入推荐曝光。
     */
    @PostMapping("/exposures")
    public Result<RecommendationExposureResult> exposures(
            @Valid @RequestBody RecommendationExposureRequest request) {
        // 1. 幂等写入，重复上报不会放大曝光
        return Result.ok(attributionService.recordExposures(request));
    }
}
