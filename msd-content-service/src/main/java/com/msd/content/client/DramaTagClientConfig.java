package com.msd.content.client;

import feign.Request;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * 标签字典 Feign 客户端专用超时配置。
 *
 * <p>只挂在 {@link DramaTagClient} 上；其他审核调用仍使用全局 Feign 超时。</p>
 */
public class DramaTagClientConfig {

    /**
     * 连接 500ms、读取 1.5s，失败由 Rex 服务回退默认标签。
     */
    @Bean
    public Request.Options dramaTagRequestOptions() {
        return new Request.Options(
                500, TimeUnit.MILLISECONDS,
                1500, TimeUnit.MILLISECONDS,
                true);
    }
}
