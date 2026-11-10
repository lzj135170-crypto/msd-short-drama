package com.msd.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 拦截外部对 /internal-source/** 的访问。
 *
 * <p>这类接口只允许服务间 Feign 直连，不能通过网关暴露给浏览器。</p>
 */
@Component
public class InternalSourceBlockFilter implements GlobalFilter, Ordered {

    /**
     * 命中 internal-source 路径时返回 404，避免暴露内部接口。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 只检查外部网关路径
        String path = exchange.getRequest().getURI().getPath();
        if (!path.contains("/internal-source/")) {
            return chain.filter(exchange);
        }

        // 2. 返回统一的 404 JSON，不透露接口是否存在
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":40400,\"message\":\"接口不存在\",\"data\":null,\"success\":false}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 在 JWT 过滤器之前执行，避免内部接口先进入鉴权流程。
     */
    @Override
    public int getOrder() {
        return -110;
    }
}
