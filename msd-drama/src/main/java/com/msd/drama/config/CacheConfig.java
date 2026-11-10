package com.msd.drama.config;

import com.msd.drama.cache.CacheProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * 多级缓存配置入口；缓存组件使用 @Component 自动注册。
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    /**
     * 延迟双删和防抖专用调度器，不占用虚拟线程。
     */
    @Bean
    public TaskScheduler cacheTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("cache-evict-");
        scheduler.initialize();
        return scheduler;
    }

    /**
     * AI 下游调用客户端；仅 dev 且 demo 开关打开时创建。
     *
     * <p>JDK HttpClient 在 socket I/O 时能卸载虚拟线程 carrier，适合高并发慢请求压测。</p>
     */
    @Bean
    @Profile("dev")
    @ConditionalOnProperty(prefix = "msd.cache.demo", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RestClient aiRestClient(CacheProperties properties) {
        CacheProperties.Demo demo = properties.getDemo();

        // 1. 只设置连接超时，读超时交给请求工厂
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(demo.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(demo.getReadTimeout());

        // 2. base URL 指向 msd-agent-service，demo 接口不经过网关
        return RestClient.builder()
                .baseUrl(demo.getAiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
