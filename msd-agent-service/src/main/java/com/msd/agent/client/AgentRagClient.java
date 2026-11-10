package com.msd.agent.client;

import com.msd.agent.client.dto.AgentRagViews.RagRecommendView;
import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * content RAG 推荐客户端，供 Agent 的 search_drama_rag 工具调用。
 */
@FeignClient(name = ServiceNames.CONTENT, contextId = "agentRagClient",
        path = "/internal/ai/rag", configuration = AgentRagFeignConfig.class)
public interface AgentRagClient {

    /**
     * 调用 RAG 推荐；用户 ID 可选，用于加载偏好画像。
     */
    @PostMapping("/recommend")
    Result<RagRecommendView> recommend(
            @RequestBody RagSearchRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId);
}
