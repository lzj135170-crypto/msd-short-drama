package com.msd.gateway.filter;

import com.msd.common.constant.CommonConstants;
import com.msd.gateway.auth.JwtTokenParser;
import com.msd.gateway.auth.JwtUser;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 鉴权过滤器：校验 Token 并向下游透传用户信息。
 *
 * <p>处理流程：
 * <ol>
 *     <li>OPTIONS 预检请求和白名单路径直接放行；</li>
 *     <li>其余请求校验 Authorization: Bearer &lt;token&gt;；</li>
 *     <li>校验通过后移除客户端伪造的身份头，写入 X-User-Id / X-User-Role；</li>
 *     <li>校验失败返回统一 401 JSON。</li>
 * </ol>
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    // 免鉴权路径，按 Ant 风格匹配
    private static final List<String> WHITELIST = List.of(
            "/api/user/auth/**", "/actuator/**", "/favicon.ico");

    // Token 解析器
    private final JwtTokenParser tokenParser;
    // 服务间内部 token；为空时 dev fail-open
    private final String internalToken;
    // Ant 风格路径匹配器
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 注入 Token 解析器和内部服务 token。
     */
    public JwtAuthenticationFilter(JwtTokenParser tokenParser,
            @Value("${msd.security.internal-token:}") String internalToken) {
        this.tokenParser = tokenParser;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    /**
     * 校验通过后注入 X-User-Id / X-User-Role。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 拿到当前请求和请求路径
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 2. 判断是否免鉴权：
        //    - OPTIONS 预检请求交给 CORS 处理
        //    - 命中白名单的路径（登录、健康检查等）直接放行
        boolean publicPath = HttpMethod.OPTIONS.equals(request.getMethod())
                || WHITELIST.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));

        // 3. 需要鉴权的请求，解析并校验 Authorization 头
        JwtUser user = null;
        if (!publicPath) {
            // 3.1 只接受标准的 Bearer Token，缺失时返回未登录
            String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return unauthorized(exchange, "未登录");
            }
            // 3.2 解析 Token；签名错误、过期、缺少声明都按无效 Token 处理
            try {
                user = tokenParser.parse(authorization.substring(7));
            } catch (JwtException | IllegalArgumentException ex) {
                // 不把具体异常暴露给客户端
                return unauthorized(exchange, "Token 无效或已过期");
            }
        }

        // 4. 重写请求头，准备透传给下游
        JwtUser currentUser = user;
        ServerHttpRequest mutated = request.mutate().headers(headers -> {
            // 4.1 先移除客户端自带的身份头和内部 token，防止伪造
            headers.remove(CommonConstants.HEADER_USER_ID);
            headers.remove(CommonConstants.HEADER_USER_ROLE);
            headers.remove(CommonConstants.HEADER_INTERNAL_TOKEN);
            // 4.2 下游只需要用户信息，不需要原始 Token
            headers.remove(HttpHeaders.AUTHORIZATION);
            // 4.3 鉴权通过时，用 Token 中的用户信息覆盖请求头
            if (currentUser != null) {
                headers.set(CommonConstants.HEADER_USER_ID, currentUser.userId());
                headers.set(CommonConstants.HEADER_USER_ROLE, currentUser.role());
            }
            // 4.4 Agent 路由注入服务端内部 token；客户端伪造值已在上一步剥离
            if (path.startsWith("/api/agent/") && !internalToken.isBlank()) {
                headers.set(CommonConstants.HEADER_INTERNAL_TOKEN, internalToken);
            }
        }).build();

        // 5. 用改写后的请求继续执行后续过滤器和路由
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 返回统一 401 响应。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        // 1. 设置 HTTP 401 和 JSON 响应类型
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // 2. 手动拼接统一响应体，避免依赖 Jackson 版本
        String body = "{\"code\":40100,\"message\":\"" + message + "\",\"data\":null,\"success\":false}";

        // 3. 写入响应体并结束请求
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 返回过滤器顺序，负值确保在路由过滤器之前执行。
     */
    @Override
    public int getOrder() {
        // 返回 -100，确保在路由过滤器之前执行鉴权
        return -100;
    }
}
