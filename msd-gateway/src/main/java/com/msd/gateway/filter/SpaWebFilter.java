package com.msd.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 单页应用深链回退：浏览器直接访问前端路由时返回 index.html。
 *
 * <p>只在 GET 且 Accept 包含 text/html 时生效，API、监控、内部接口、
 * 测试台和带扩展名的静态资源都不受影响。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SpaWebFilter implements WebFilter {

    // 不参与 SPA 回退的路径前缀
    private static final List<String> SKIP_PREFIXES = List.of(
            "/api", "/actuator", "/internal", "/console");

    /**
     * 把浏览器导航请求重写到前端入口。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 1. 只处理 GET 页面请求
        ServerHttpRequest request = exchange.getRequest();
        if (!HttpMethod.GET.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        // 2. API、监控、内部接口和测试台保持原路径
        String path = request.getPath().value();
        if (SKIP_PREFIXES.stream().anyMatch(prefix -> matchesPrefix(path, prefix))) {
            return chain.filter(exchange);
        }

        // 3. 带扩展名的静态资源交给默认资源处理器
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (lastSegment.contains(".")) {
            return chain.filter(exchange);
        }

        // 4. 只接管浏览器的 HTML 导航，避免影响接口调试工具
        String accept = request.getHeaders().getFirst(HttpHeaders.ACCEPT);
        if (accept == null || !accept.contains("text/html")) {
            return chain.filter(exchange);
        }

        // 5. 重写到入口文件，原始 URL 由 Vue Router 解析
        ServerHttpRequest rewritten = request.mutate().path("/index.html").build();
        return chain.filter(exchange.mutate().request(rewritten).build());
    }

    /**
     * 判断路径是否等于前缀或位于前缀目录下。
     */
    private boolean matchesPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
