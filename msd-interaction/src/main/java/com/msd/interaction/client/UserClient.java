package com.msd.interaction.client;

import com.msd.common.constant.ServiceNames;
import com.msd.common.dto.IdBatchRequest;
import com.msd.common.dto.UserInfoDTO;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 用户服务 Feign 客户端。
 * name 为 Nacos 服务名，LoadBalancer 从实例列表中选一个节点。
 */
@FeignClient(name = ServiceNames.USER, path = "/internal/users", configuration = FeignConfig.class)
public interface UserClient {

    /**
     * 按 ID 查询用户信息。
     */
    @GetMapping("/{id}")
    Result<UserInfoDTO> getById(@PathVariable("id") Long id);

    /**
     * 批量查询用户公开信息，供评论列表补齐作者。
     */
    @PostMapping("/batch")
    Result<List<UserInfoDTO>> getByIds(@RequestBody IdBatchRequest request);
}
