package com.msd.content.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 短剧标签字典只读客户端。
 *
 * <p>独立 Feign 配置使用短超时，避免标签字典拖慢审核主链路。</p>
 */
@FeignClient(name = ServiceNames.DRAMA, contextId = "dramaTagClient",
        path = "/internal-source/reviews", configuration = DramaTagClientConfig.class)
public interface DramaTagClient {

    /**
     * 查询启用中的标签名称，用于 Rex 标签候选。
     */
    @GetMapping("/tag-dictionary")
    Result<List<String>> tagDictionary();
}
