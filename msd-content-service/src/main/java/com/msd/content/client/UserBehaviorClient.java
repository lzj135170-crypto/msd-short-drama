package com.msd.content.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.dto.UserBehaviorDTO;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户行为只读客户端，直接调用 interaction 的内部接口。
 */
@FeignClient(name = ServiceNames.INTERACTION, contextId = "userBehaviorClient",
        path = "/internal-source")
public interface UserBehaviorClient {

    /**
     * 查询用户短剧行为权重。
     */
    @GetMapping("/users/{userId}/behavior")
    Result<UserBehaviorDTO> behavior(@PathVariable("userId") Long userId);
}
