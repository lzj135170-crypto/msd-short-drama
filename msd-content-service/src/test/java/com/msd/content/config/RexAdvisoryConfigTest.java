package com.msd.content.config;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rex 客户端必须使用 HTTP/1.1，避免 uvicorn 拒绝 JDK 的 HTTP/2 升级请求。
 */
class RexAdvisoryConfigTest {

    private final RexAdvisoryConfig config = new RexAdvisoryConfig();

    /**
     * HTTP 客户端固定 HTTP/1.1，线程池是有界池。
     */
    @Test
    void usesHttp11AndBoundedExecutor() {
        HttpClient client = config.rexAdvisoryHttpClient(new RexAdvisoryProperties());

        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);

        ExecutorService executor = config.reviewAdvisoryExecutor();
        assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
        assertThat(pool.getQueue().remainingCapacity()).isEqualTo(64);
        executor.shutdownNow();
    }
}
