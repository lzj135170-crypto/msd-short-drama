package com.msd.agent.client;

import feign.Request;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * RAG 推荐 Feign 超时配置。
 *
 * <p>推荐链路包含检索、Rerank 和模型生成，读取超时单独放宽到 35 秒，
 * 不影响 drama/companion 等短超时客户端。</p>
 */
public class AgentRagFeignConfig {

    /**
     * 连接 2 秒、读取 35 秒。
     */
    @Bean
    public Request.Options ragRequestOptions() {
        return new Request.Options(2_000, TimeUnit.MILLISECONDS,
                35_000, TimeUnit.MILLISECONDS, true);
    }
}
