package com.msd.content.audit;

import com.msd.content.config.RexAdvisoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RexUniNLU HTTP 客户端契约与 fail-open 测试。
 */
class RexAdvisoryClientTest {

    private final HttpClient httpClient = mock(HttpClient.class);
    private final HttpResponse<String> httpResponse = mock(HttpResponse.class);
    private final RexAdvisoryProperties properties = new RexAdvisoryProperties();
    private final RexAdvisoryClient client =
            new RexAdvisoryClient(httpClient, properties, JsonMapper.shared());

    /**
     * 统一设置客户端配置和默认响应。
     */
    @BeforeEach
    void setUp() throws Exception {
        properties.setBaseUrl("http://127.0.0.1:8108");
        properties.setAnalyzePath("/v1/analyze");
        properties.setTimeoutMs(8000);
        properties.setInternalToken("secret");
        when(httpClient.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);
    }

    /**
     * 正常响应解析为强类型 DTO，并带内部 token。
     */
    @Test
    void parsesSuccessfulResponse() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("""
                {"status":"ok","model":"iic/nlp_deberta_rex-uninlu_chinese-base",
                 "revision":"v1.0.2","device":"cpu","chunks":2,"failedChunks":0,
                 "truncated":false,
                 "sentiment":{"label":"negative","rawLabel":"负向情感","highNegative":true,
                              "support":2,"evidence":["刑警追查十年悬案"]},
                 "tags":[{"name":"悬疑","support":2,"evidence":["雨夜连环案"]}],
                 "degraded":false,"degradedFlags":[],"warnings":[],"elapsedMs":842}
                """);

        RexAnalyzeResponse response = client.analyze(request());

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.sentiment().highNegative()).isTrue();
        assertThat(response.tags()).singleElement()
                .satisfies(tag -> assertThat(tag.name()).isEqualTo("悬疑"));
        verify(httpClient).send(ArgumentMatchers.argThat(request ->
                        request.headers().firstValue("X-Internal-Token").orElse("").equals("secret")),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    /**
     * 非 2xx 返回降级结果，不抛异常。
     */
    @Test
    void degradesOnNon2xx() {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("{}");

        RexAnalyzeResponse response = client.analyze(request());

        assertThat(response.degraded()).isTrue();
        assertThat(response.degradedFlags()).contains("rex_unavailable");
    }

    /**
     * 401 视为协议错误，便于区分 token 配置问题。
     */
    @Test
    void flagsProtocolErrorOn401() {
        when(httpResponse.statusCode()).thenReturn(401);
        when(httpResponse.body()).thenReturn("{}");

        RexAnalyzeResponse response = client.analyze(request());

        assertThat(response.degraded()).isTrue();
        assertThat(response.degradedFlags()).contains("rex_protocol_error");
    }

    /**
     * JSON 解析失败不阻断审核。
     */
    @Test
    void degradesOnMalformedJson() {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("not-json");

        RexAnalyzeResponse response = client.analyze(request());

        assertThat(response.degraded()).isTrue();
        assertThat(response.degradedFlags()).contains("rex_parse_error");
    }

    /**
     * 连接异常按不可用降级。
     */
    @Test
    void degradesOnIoError() throws Exception {
        when(httpClient.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("connection refused"));

        RexAnalyzeResponse response = client.analyze(request());

        assertThat(response.degraded()).isTrue();
        assertThat(response.degradedFlags()).contains("rex_unavailable");
    }

    /**
     * 超时单独标记，便于与连接失败区分。
     */
    @Test
    void degradesOnTimeout() throws Exception {
        when(httpClient.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new HttpTimeoutException("timeout"));

        RexAnalyzeResponse response = client.analyze(request());

        assertThat(response.degraded()).isTrue();
        assertThat(response.degradedFlags()).contains("rex_timeout");
    }

    /**
     * 构造分析请求。
     */
    private RexAnalyzeRequest request() {
        return new RexAnalyzeRequest("5003", "雾城谜案", List.of("正向情感", "负向情感"),
                List.of("悬疑"));
    }
}
