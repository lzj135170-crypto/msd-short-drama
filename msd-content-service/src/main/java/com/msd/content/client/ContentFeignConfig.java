package com.msd.content.client;

import feign.Logger;
import org.springframework.context.annotation.Bean;

/**
 * 内容服务 Feign 配置：只调整目录导出客户端的日志级别。
 *
 * <p>通过 {@code @FeignClient(configuration = ...)} 挂在 {@link DramaCatalogClient} 上，
 * 不会影响其他 Feign 客户端的默认配置。</p>
 */
public class ContentFeignConfig {

    /**
     * 输出基础请求日志，便于排查短剧目录导出调用。
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
