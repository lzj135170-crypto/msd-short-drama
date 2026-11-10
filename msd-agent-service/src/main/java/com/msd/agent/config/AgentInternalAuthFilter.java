package com.msd.agent.config;

import com.msd.common.constant.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Supervisor 8105 内部接口鉴权：要求网关或服务端注入 X-Internal-Token。
 *
 * <p>dev 环境 token 为空时 fail-open 并告警；生产必须设置 MSD_INTERNAL_TOKEN。</p>
 */
@Component
@org.springframework.context.annotation.Profile("!audit")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AgentInternalAuthFilter implements WebFilter {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AgentInternalAuthFilter.class);

    // 期望的内部 token；空值表示 dev fail-open
    private final String expectedToken;

    /**
     * 注入内部 token。
     */
    public AgentInternalAuthFilter(
            @Value("${msd.security.internal-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
        if (!StringUtils.hasText(this.expectedToken)) {
            log.warn("msd.security.internal-token is empty; internal auth fails open in dev");
        }
    }

    /**
     * 校验 token；actuator 和 OPTIONS 放行。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        // 1. 健康检查和预检请求放行
        if (!StringUtils.hasText(expectedToken)
                || "OPTIONS".equalsIgnoreCase(request.getMethod().name())
                || path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        // 2. 常量时间比较，避免 token 被时序探测
        String actual = request.getHeaders().getFirst(CommonConstants.HEADER_INTERNAL_TOKEN);
        if (actual != null && constantTimeEquals(expectedToken, actual.trim())) {
            return chain.filter(exchange);
        }
        return unauthorized(exchange, "缺少或非法内部令牌");
    }

    /**
     * 比较两个 UTF-8 字符串。
     */
    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 返回统一 401 JSON。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":40100,\"message\":\"" + message
                + "\",\"data\":null,\"success\":false}";
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
