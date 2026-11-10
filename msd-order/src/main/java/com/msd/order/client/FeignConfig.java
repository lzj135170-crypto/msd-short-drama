package com.msd.order.client;

import feign.Logger;
import org.springframework.context.annotation.Bean;

/**
 * 订单服务的 Feign 配置。
 */
public class FeignConfig {

    /**
     * 输出基础请求日志，便于排查远程调用。
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
