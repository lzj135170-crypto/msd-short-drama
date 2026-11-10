package com.msd.interaction.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import com.msd.interaction.dto.CommentSummaryClientRequest;
import com.msd.interaction.dto.CommentSummaryClientResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * content 评论课代表客户端，独立 15 秒读超时。
 */
@FeignClient(name = ServiceNames.CONTENT, contextId = "commentSummaryClient",
        path = "/internal/ai", configuration = CommentSummaryFeignConfig.class)
public interface CommentSummaryClient {

    /**
     * 生成评论摘要。
     */
    @PostMapping("/comment-summary")
    Result<CommentSummaryClientResponse> summarize(
            @RequestBody CommentSummaryClientRequest request);
}
