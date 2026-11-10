package com.msd.agent.client;

import feign.Logger;
import org.springframework.context.annotation.Bean;

/**
 * Agent 服务 Feign 客户端配置。
 *
 * <p>不加 @Configuration，避免被全局组件扫描，只作用于声明它的客户端。</p>
 */
public class AgentFeignConfig {

    /**
     * 输出请求方法、URL、响应状态和耗时。
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
