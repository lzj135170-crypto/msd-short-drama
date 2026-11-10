package com.msd.interaction.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import com.msd.interaction.dto.CommentAuditRequest;
import com.msd.interaction.dto.CommentAuditResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * content 结构化审核客户端，复用 15 秒读超时配置。
 */
@FeignClient(name = ServiceNames.CONTENT, contextId = "commentAuditClient",
        path = "/internal/ai", configuration = CommentSummaryFeignConfig.class)
public interface CommentAuditClient {

    /**
     * 审核一条评论。
     */
    @PostMapping("/audit/structured")
    Result<CommentAuditResponse> audit(@RequestBody CommentAuditRequest request);
}
