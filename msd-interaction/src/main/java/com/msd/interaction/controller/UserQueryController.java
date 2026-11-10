package com.msd.interaction.controller;

import com.msd.common.dto.UserInfoDTO;
import com.msd.common.result.Result;
import com.msd.interaction.client.UserClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动服务查询用户信息的示例接口。
 */
@RestController
@RequestMapping("/internal/users")
public class UserQueryController {

    // 用户服务 Feign 客户端，按服务名 msd-user 发起调用
    private final UserClient userClient;

    /**
     * 注入用户服务 Feign 客户端。
     */
    public UserQueryController(UserClient userClient) {
        this.userClient = userClient;
    }

    /**
     * 通过 Feign 调用用户服务，返回用户信息。
     */
    @GetMapping("/{id}/profile")
    public Result<UserInfoDTO> profile(@PathVariable Long id) {
        // 1. 交给 Feign 客户端按服务名 msd-user 发起调用
        // 2. LoadBalancer 从 Nacos 实例列表中选择一个节点
        // 3. 用户服务返回的 Result 原样透传给调用方
        return userClient.getById(id);
    }
}
