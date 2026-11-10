package com.msd.interaction.client;

import feign.Logger;
import feign.Request;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * 评论课代表 Feign 配置：独立 15 秒读超时，避免复用互动默认的 800ms。
 *
 * <p>不加 @Configuration，避免影响其他 Feign 客户端。</p>
 */
public class CommentSummaryFeignConfig {

    /**
     * 评论摘要允许模型生成较慢，连接 1 秒、读取 15 秒。
     */
    @Bean
    public Request.Options commentSummaryOptions() {
        return new Request.Options(
                Duration.ofSeconds(1), Duration.ofSeconds(15), true);
    }

    /**
     * 输出基础请求日志。
     */
    @Bean
    public Logger.Level commentSummaryLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
