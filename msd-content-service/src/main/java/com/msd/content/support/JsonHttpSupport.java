package com.msd.content.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 基于 JDK HttpClient 的 JSON 请求工具。
 *
 * <p>只负责发送请求和返回状态码/响应体，HTTP 非 2xx 由调用方按业务决定是否降级。</p>
 */
public final class JsonHttpSupport {

    /**
     * 工具类禁止实例化。
     */
    private JsonHttpSupport() {
    }

    /**
     * 发送 JSON 请求。
     *
     * @param client    复用的 HttpClient
     * @param method    HTTP 方法
     * @param url      完整地址
     * @param jsonBody  请求体，GET/DELETE 传 null
     * @param timeoutMs 单次请求超时
     */
    public static HttpResult send(HttpClient client, String method, String url,
                                  String jsonBody, long timeoutMs) {
        return send(client, method, url, jsonBody, timeoutMs, Map.of());
    }

    /**
     * 发送 JSON 请求并附加自定义请求头。
     */
    public static HttpResult send(HttpClient client, String method, String url,
                                  String jsonBody, long timeoutMs, Map<String, String> headers) {
        // 1. 超时至少 100ms，避免配置为 0 导致请求立即失败
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(timeoutMs, 100)));

        // 2. 有请求体时声明 JSON，GET/DELETE 使用空 body
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }

        // 3. 附加调用方请求头，例如 Rerank 的 Authorization
        if (headers != null) {
            headers.forEach(builder::header);
        }

        try {
            HttpResponse<String> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RagUnavailableException("HTTP 请求被中断", e);
        } catch (IOException e) {
            throw new RagUnavailableException("HTTP 请求失败：" + e.getMessage(), e);
        }
    }

    /**
     * HTTP 响应状态码和响应体。
     *
     * @param status HTTP 状态码
     * @param body   响应体原文
     */
    public record HttpResult(int status, String body) {

        /**
         * 是否为 2xx。
         */
        public boolean successful() {
            return status >= 200 && status < 300;
        }
    }
}
