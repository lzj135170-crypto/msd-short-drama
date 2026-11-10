package com.msd.agent.config;

import com.msd.agent.routing.PromptGuard;
import com.msd.common.constant.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * audit 8106 A2A 入口鉴权和内容防护。
 *
 * <p>AgentCard 保持只读可发现；JSON-RPC 请求要求 X-Internal-Token，
 * 并对请求体做长度和 Prompt 注入检查。</p>
 */
@Component
@org.springframework.context.annotation.Profile("audit")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuditInternalAuthFilter implements WebFilter {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(AuditInternalAuthFilter.class);
    /** 请求体最大字节数，审核内容正常不超过 5K 字。 */
    private static final int MAX_BODY_BYTES = 128 * 1024;

    // 期望的内部 token；空值表示 dev fail-open
    private final String expectedToken;
    // Prompt 防护
    private final PromptGuard promptGuard;

    /**
     * 注入内部 token 和 Prompt 防护。
     */
    public AuditInternalAuthFilter(
            @Value("${msd.security.internal-token:}") String expectedToken,
            PromptGuard promptGuard) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
        this.promptGuard = promptGuard;
        if (!StringUtils.hasText(this.expectedToken)) {
            log.warn("msd.security.internal-token is empty; audit A2A auth fails open in dev");
        }
    }

    /**
     * 校验 AgentCard 白名单、token 和请求体。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. AgentCard、actuator 和 OPTIONS 保持可发现
        if (path.startsWith("/.well-known/agent-card.json")
                || path.startsWith("/actuator")
                || HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        // 2. token 为空时 dev fail-open
        if (!StringUtils.hasText(expectedToken)) {
            return inspectBody(exchange, chain);
        }
        String actual = request.getHeaders().getFirst(CommonConstants.HEADER_INTERNAL_TOKEN);
        if (actual == null || !constantTimeEquals(expectedToken, actual.trim())) {
            return error(exchange, HttpStatus.UNAUTHORIZED, "缺少或非法内部令牌");
        }
        return inspectBody(exchange, chain);
    }

    /**
     * 只对 POST JSON-RPC 读取有限大小的请求体，检查注入语句后原样回放。
     */
    private Mono<Void> inspectBody(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!HttpMethod.POST.equals(request.getMethod())) {
            return chain.filter(exchange);
        }
        long contentLength = request.getHeaders().getContentLength();
        if (contentLength > MAX_BODY_BYTES) {
            return error(exchange, HttpStatus.valueOf(413), "审核内容过大");
        }

        return DataBufferUtils.join(request.getBody())
                // 1. 先读取并判定，避免 flatMap 返回 Mono<Void> 后被 switchIfEmpty 再次执行
                .map(buffer -> inspectBuffer(buffer))
                .defaultIfEmpty(new BodyCheck(null, false, ""))
                .flatMap(body -> {
                    if (body.rejected()) {
                        return error(exchange, HttpStatus.BAD_REQUEST,
                                "审核内容包含不被允许的指令注入");
                    }
                    // 2. 空请求体直接放行
                    if (body.bytes() == null) {
                        return chain.filter(exchange);
                    }
                    // 3. 用新 buffer 回放给 A2A 控制器
                    ServerHttpRequest replay = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(exchange.getResponse().bufferFactory()
                                    .wrap(body.bytes()));
                        }
                    };
                    return chain.filter(exchange.mutate().request(replay).build());
                });
    }

    /**
     * 读取并释放原 buffer，返回注入检查结果。
     */
    private BodyCheck inspectBuffer(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        DataBufferUtils.release(buffer);
        PromptGuard.CheckResult check =
                promptGuard.inspect(new String(bytes, StandardCharsets.UTF_8));
        return new BodyCheck(bytes, check.rejected(), check.reason());
    }

    /**
     * 比较两个 UTF-8 字符串。
     */
    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 请求体检查结果；bytes 为空表示没有请求体。
     */
    private record BodyCheck(byte[] bytes, boolean rejected, String reason) {
    }

    /**
     * 返回统一错误 JSON。
     */
    private Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + status.value() + "00,\"message\":\"" + message
                + "\",\"data\":null,\"success\":false}";
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
