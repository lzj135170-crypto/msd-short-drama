package com.msd.agent.controller;

import com.msd.common.constant.CommonConstants;
import com.msd.common.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务探活接口，同时回显网关透传的用户信息。
 */
@RestController
@RequestMapping("/internal")
public class PingController {

    private final String serviceName; // spring.application.name
    private final String port;        // server.port
    private final String env;         // Nacos common.yaml 中的 msd.env.name

    /**
     * 通过构造器注入服务名、端口和环境。
     */
    public PingController(@Value("${spring.application.name}") String serviceName,
                          @Value("${server.port}") String port,
                          @Value("${msd.env.name:unknown}") String env) {
        this.serviceName = serviceName;
        this.port = port;
        this.env = env;
    }

    /**
     * 返回服务信息、环境和网关透传的用户信息。
     */
    @GetMapping("/ping")
    public Result<Map<String, String>> ping(
            @RequestHeader(value = CommonConstants.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = CommonConstants.HEADER_USER_ROLE, required = false) String role) {
        // 1. serviceName / port / env 来自本服务配置
        // 2. userId / role 由网关 JwtAuthenticationFilter 注入，直连服务时为空
        // 3. Map.of 不允许 null，这里统一转成空串
        return Result.ok(Map.of(
                "service", serviceName,
                "port", port,
                "env", env,
                "userId", userId == null ? "" : userId,
                "role", role == null ? "" : role));
    }
}
