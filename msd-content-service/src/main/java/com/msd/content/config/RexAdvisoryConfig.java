package com.msd.content.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rex 建议的 HTTP 客户端和隔离线程池。
 *
 * <p>线程池只承载 advisory 调用，避免模型慢请求占满内容服务的主线程池。</p>
 */
@Configuration
@EnableConfigurationProperties(RexAdvisoryProperties.class)
public class RexAdvisoryConfig {

    /**
     * 独立的 JDK HttpClient，连接超时由 Rex 配置控制。
     */
    @Bean("rexAdvisoryHttpClient")
    public HttpClient rexAdvisoryHttpClient(RexAdvisoryProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(properties.getConnectTimeoutMs(), 100)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * advisory 专用线程池；队列满时由调用线程执行，形成天然背压。
     */
    @Bean(name = "reviewAdvisoryExecutor", destroyMethod = "shutdown")
    public ExecutorService reviewAdvisoryExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "rex-advisory-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
