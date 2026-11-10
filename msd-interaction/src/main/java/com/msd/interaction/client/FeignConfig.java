package com.msd.interaction.client;

import feign.Logger;
import org.springframework.context.annotation.Bean;

/**
 * Feign 客户端配置。
 * 不加 @Configuration，避免被全局组件扫描，只作用于声明它的客户端。
 */
public class FeignConfig {

    /**
     * 输出请求方法、URL、响应状态和耗时。
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
