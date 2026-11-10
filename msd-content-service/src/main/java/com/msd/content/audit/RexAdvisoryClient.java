package com.msd.content.audit;

import com.msd.content.config.RexAdvisoryProperties;
import com.msd.content.support.JsonHttpSupport;
import com.msd.content.support.RagUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.Map;

/**
 * Rex sidecar HTTP 客户端。
 *
 * <p>非 2xx、超时、网络异常和 JSON 解析失败全部转换为降级响应，不向审核链路抛异常。</p>
 */
@Component
public class RexAdvisoryClient {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(RexAdvisoryClient.class);

    // sidecar 专用 HttpClient
    private final HttpClient httpClient;
    // Rex 配置
    private final RexAdvisoryProperties properties;
    // JSON 序列化
    private final JsonMapper jsonMapper;

    /**
     * 注入 HTTP 客户端、配置和 JSON 序列化器。
     */
    public RexAdvisoryClient(@Qualifier("rexAdvisoryHttpClient") HttpClient httpClient,
                             RexAdvisoryProperties properties,
                             JsonMapper jsonMapper) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 调用 sidecar；任何失败都返回 degraded 响应。
     */
    public RexAnalyzeResponse analyze(RexAnalyzeRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            // 1. 序列化请求并附加内部 token
            String body = jsonMapper.writeValueAsString(request);
            Map<String, String> headers = properties.getInternalToken() == null
                    || properties.getInternalToken().isBlank()
                    ? Map.of() : Map.of("X-Internal-Token", properties.getInternalToken());
            JsonHttpSupport.HttpResult result = JsonHttpSupport.send(
                    httpClient, "POST", endpoint(), body,
                    Math.max(properties.getTimeoutMs(), 100), headers);

            // 2. 非 2xx 按协议错误或不可用降级
            if (!result.successful()) {
                String flag = result.status() == 400 || result.status() == 401
                        || result.status() == 422 ? "rex_protocol_error" : "rex_unavailable";
                log.warn("rex advisory non-2xx: requestId={}, status={}, flag={}",
                        request.requestId(), result.status(), flag);
                return RexAnalyzeResponse.degraded(flag, elapsed(startedAt));
            }

            // 3. 解析响应；结构异常按解析失败降级
            RexAnalyzeResponse response = jsonMapper.readValue(
                    result.body(), RexAnalyzeResponse.class);
            if (response == null) {
                return RexAnalyzeResponse.degraded("rex_parse_error", elapsed(startedAt));
            }
            return response;
        } catch (RagUnavailableException ex) {
            String flag = isTimeout(ex) ? "rex_timeout" : "rex_unavailable";
            log.warn("rex advisory unavailable: requestId={}, flag={}, error={}",
                    request.requestId(), flag, rootMessage(ex));
            return RexAnalyzeResponse.degraded(flag, elapsed(startedAt));
        } catch (JacksonException ex) {
            log.warn("rex advisory parse failed: requestId={}, error={}",
                    request.requestId(), rootMessage(ex));
            return RexAnalyzeResponse.degraded("rex_parse_error", elapsed(startedAt));
        } catch (Exception ex) {
            log.warn("rex advisory call failed: requestId={}, error={}",
                    request.requestId(), rootMessage(ex));
            return RexAnalyzeResponse.degraded("rex_unavailable", elapsed(startedAt));
        }
    }

    /**
     * 拼接 baseUrl 和 analyzePath，避免重复斜杠。
     */
    private String endpoint() {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = properties.getAnalyzePath() == null
                ? "/v1/analyze" : properties.getAnalyzePath().trim();
        if (path.isEmpty()) {
            path = "/v1/analyze";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    /**
     * 判断根因是否为超时。
     */
    private boolean isTimeout(Throwable throwable) {
        Throwable current = rootCause(throwable);
        if (current instanceof HttpTimeoutException) {
            return true;
        }
        String message = current.getMessage() == null
                ? "" : current.getMessage().toLowerCase();
        return message.contains("timeout") || message.contains("timed out");
    }

    /**
     * 取最内层异常。
     */
    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable cause = rootCause(throwable);
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    /**
     * 计算耗时。
     */
    private long elapsed(long startedAt) {
        return Math.max(0, System.currentTimeMillis() - startedAt);
    }
}
