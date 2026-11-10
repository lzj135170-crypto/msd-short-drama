package com.msd.content.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.dto.DramaAiReviewResultRequest;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 短剧服务审核内部接口，只走服务间直连。
 */
@FeignClient(name = ServiceNames.DRAMA, contextId = "dramaReviewClient",
        path = "/internal-source/reviews", configuration = ContentFeignConfig.class)
public interface DramaReviewClient {

    /**
     * 查询待 AI 审核的短剧候选；includeFailed=true 时同时返回 AI 失败项。
     */
    @GetMapping("/pending")
    Result<List<DramaReviewCandidateDTO>> pending(@RequestParam("limit") int limit,
                                                  @RequestParam("includeFailed") boolean includeFailed);

    /**
     * 查询待审短剧详情。
     */
    @GetMapping("/{dramaId}")
    Result<DramaReviewCandidateDTO> detail(@PathVariable("dramaId") Long dramaId);

    /**
     * 写回 AI 审核结果；只在待审状态生效。
     */
    @PutMapping("/{dramaId}/ai-result")
    Result<Boolean> writeAiResult(@PathVariable("dramaId") Long dramaId,
                                  @RequestBody DramaAiReviewResultRequest request);
}
