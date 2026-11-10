package com.msd.agent.service;

import com.msd.agent.dto.RemoteAuditReply;
import com.msd.common.constant.CommonConstants;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import io.a2a.client.transport.spi.interceptors.ClientCallContext;
import io.a2a.client.transport.spi.interceptors.ClientCallInterceptor;
import io.a2a.client.transport.spi.interceptors.PayloadAndHeaders;
import io.a2a.spec.A2AClientHTTPError;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.A2aAgentConfig;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Supervisor 侧的远程 audit-agent 调用。
 *
 * <p>每次调用从 Nacos 解析最新 AgentCard，并创建一个新的 {@link A2aAgent}；
 * A2aAgent 内部持有请求上下文，官方明确不能并发复用。失败时做有限重试，
 * 最终失败返回降级标记，由 Supervisor 回退本地 audit-agent。</p>
 */
@Service
@Profile("!audit")
public class RemoteAuditService {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(RemoteAuditService.class);

    /** 远程 Endpoint 预检超时；避免 A2A SDK 在服务不可达时长时间挂起。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** 预检专用 JDK HttpClient，连接超时短于业务请求超时。 */
    private static final HttpClient PROBE_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    // 懒加载的 Nacos AgentCard 解析器，避免启动时强依赖 Nacos
    private final ObjectProvider<NacosAgentCardResolver> resolverProvider;
    // 远程审核 Agent 在 Nacos 中登记的名称
    private final String agentName;
    // 单次远程调用超时时间
    private final Duration requestTimeout;
    // 失败后的最大重试次数
    private final int maxRetries;
    // 内部调用 token；为空时 dev fail-open
    private final String internalToken;

    /**
     * 注入懒加载的 AgentCard 解析器、远程调用参数和内部 token。
     */
    public RemoteAuditService(ObjectProvider<NacosAgentCardResolver> resolverProvider,
            @Value("${msd.ai.a2a.remote.agent-name:audit-agent}") String agentName,
            @Value("${msd.ai.a2a.remote.request-timeout:60s}") Duration requestTimeout,
            @Value("${msd.ai.a2a.remote.max-retries:2}") int maxRetries,
            @Value("${msd.security.internal-token:}") String internalToken) {
        this.resolverProvider = resolverProvider;
        this.agentName = agentName;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    /**
     * 在 Spring 启动线程上预初始化 A2A SDK 的传输注册表。
     *
     * <p>A2A SDK 用 ServiceLoader + 线程上下文类加载器发现 JSON-RPC Provider；
     * 如果首次初始化发生在 Reactor/Netty 线程，fat jar 内的 SPI 文件可能不可见，
     * 导致 ClientBuilder 报 {@code No compatible transport found}。这里提前在主线程触发，
     * 确保静态注册表完整。</p>
     */
    @PostConstruct
    void initializeA2aTransportRegistry() {
        try {
            // 1. 反射读取 A2A ClientBuilder 的静态传输注册表
            Class<?> builderClass = Class.forName("io.a2a.client.ClientBuilder", true,
                    RemoteAuditService.class.getClassLoader());
            Field field = builderClass.getDeclaredField("transportProtocolMapping");
            field.setAccessible(true);
            Map<?, ?> mapping = (Map<?, ?>) field.get(null);
            // 2. 注册表为空说明 SPI 尚未加载，远程审核只能降级
            if (mapping.isEmpty()) {
                log.error("A2A client transport registry is empty; remote audit will degrade");
            } else {
                log.info("A2A client transport registry initialized: {}", mapping.keySet());
            }
        } catch (ReflectiveOperationException ex) {
            log.warn("Failed to pre-initialize A2A client transport registry", ex);
        }
    }

    /**
     * 调用远程 audit-agent；重试仍失败时返回降级结果，不把异常抛给上游。
     */
    public Mono<RemoteAuditReply> audit(String content, String userId, String sessionId) {
        log.info("Remote audit start: agent={}, userId={}, sessionId={}",
                agentName, userId, sessionId);

        return Mono.defer(() -> {
                    // 1. 每次调用重新解析 AgentCard，nacos 地址变化无需重启
                    NacosAgentCardResolver resolver = resolverProvider.getObject();
                    AgentCard agentCard = normalize(resolver.getAgentCard(agentName));
                    log.info("Resolved A2A AgentCard: name={}, url={}, transport={}, interfaces={}",
                            agentCard.name(), agentCard.url(), agentCard.preferredTransport(),
                            agentCard.additionalInterfaces());

                    // 2. 先探活 AgentCard 地址；服务已停时快速失败，不等 A2A 客户端超时
                    return probe(agentCard).then(Mono.defer(() -> {
                        // 3. A2aAgent 不是线程安全的，按调用创建，不能做成单例；
                        // 使用本次解析的卡片快照，避免调用过程中重复解析
                        A2aAgent.Builder agentBuilder = A2aAgent.builder()
                                .name(agentName)
                                .agentCard(agentCard)
                                .checkRunning(false);
                        // 4. 服务端开启内部鉴权时，为 JSON-RPC 请求统一附加内部 token
                        if (StringUtils.hasText(internalToken)) {
                            JSONRPCTransportConfig transportConfig =
                                    new JSONRPCTransportConfigBuilder()
                                            .addInterceptor(new InternalTokenInterceptor(internalToken))
                                            .build();
                            agentBuilder.a2aAgentConfig(A2aAgentConfig.builder()
                                    .withTransport(JSONRPCTransport.class, transportConfig)
                                    .build());
                        }
                        return agentBuilder.build().call(List.of(new UserMessage(content)));
                    }));
                })
                // 3. 单次远程调用超时；超时同样进入重试
                .timeout(requestTimeout)
                .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(300))
                        .filter(RemoteAuditService::retryable)
                        .doBeforeRetry(signal -> log.warn(
                                "Remote audit retry {}/{}: {}",
                                signal.totalRetries() + 1,
                                maxRetries,
                                describe(signal.failure()))))
                .switchIfEmpty(Mono.error(
                        new IllegalStateException("Remote audit returned empty response")))
                .map(message -> new RemoteAuditReply(
                        message.getTextContent(), "remote", false, agentName, sessionId))
                .doOnSuccess(reply -> log.info(
                        "Remote audit done: agent={}, sessionId={}", agentName, sessionId))
                // 4. 远程彻底不可用：转成降级结果，Supervisor 据此回退本地 audit-agent
                .onErrorResume(ex -> {
                    log.error("Remote audit unavailable: agent={}, sessionId={}",
                            agentName, sessionId, ex);
                    return Mono.just(new RemoteAuditReply(
                            "远程 audit-agent 暂不可用，请转本地审核。",
                            "local-fallback", true, agentName, sessionId));
                });
    }

    /**
     * 规范化 Nacos 返回的 AgentCard：补齐 preferredTransport 和 additionalInterfaces。
     *
     * <p>A2A SDK 依赖卡片中的传输字段做协商；Nacos 卡片字段缺失时统一补成 JSON-RPC，
     * 避免把不完整卡片传给客户端。</p>
     */
    private AgentCard normalize(AgentCard card) {
        // 1. 卡片缺失时直接失败，交由上层重试或降级
        if (card == null) {
            throw new IllegalStateException("AgentCard not found for agent " + agentName);
        }
        // 2. 补齐 preferredTransport，默认 JSON-RPC
        String transport = StringUtils.hasText(card.preferredTransport())
                ? card.preferredTransport()
                : TransportProtocol.JSONRPC.asString();
        // 3. interfaces 缺失时用卡片 URL 构造一条
        List<AgentInterface> interfaces = card.additionalInterfaces();
        if (interfaces == null || interfaces.isEmpty()) {
            interfaces = List.of(new AgentInterface(transport, card.url()));
        }
        // 4. 用补齐后的传输信息重建卡片
        return new AgentCard.Builder()
                .name(card.name())
                .description(card.description())
                .url(card.url())
                .provider(card.provider())
                .version(card.version())
                .documentationUrl(card.documentationUrl())
                .capabilities(card.capabilities())
                .defaultInputModes(card.defaultInputModes())
                .defaultOutputModes(card.defaultOutputModes())
                .skills(card.skills())
                .supportsAuthenticatedExtendedCard(card.supportsAuthenticatedExtendedCard())
                .securitySchemes(card.securitySchemes())
                .security(card.security())
                .iconUrl(card.iconUrl())
                .additionalInterfaces(interfaces)
                .preferredTransport(transport)
                .protocolVersion(card.protocolVersion())
                .build();
    }

    /**
     * 预检 AgentCard 的 well-known 地址；失败会进入重试，最终降级。
     */
    private Mono<Void> probe(AgentCard agentCard) {
        // 1. 校验卡片 URL 存在且包含 scheme 与主机
        if (agentCard == null || !StringUtils.hasText(agentCard.url())) {
            return Mono.error(new IllegalStateException(
                    "AgentCard url is empty for agent " + agentName));
        }
        URI cardUri = URI.create(agentCard.url());
        if (!StringUtils.hasText(cardUri.getScheme())
                || !StringUtils.hasText(cardUri.getAuthority())) {
            return Mono.error(new IllegalStateException(
                    "Invalid AgentCard url for agent " + agentName + ": " + agentCard.url()));
        }
        String baseUrl = cardUri.getScheme() + "://" + cardUri.getAuthority();
        // 2. 请求 well-known 卡片地址，仅 200 视为服务可用
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/.well-known/agent-card.json"))
                .timeout(PROBE_TIMEOUT)
                .GET();
        // AgentCard 本身只读，但审计服务前置过滤器可能要求 token，这里一并带上
        if (StringUtils.hasText(internalToken)) {
            requestBuilder.header(CommonConstants.HEADER_INTERNAL_TOKEN, internalToken);
        }
        HttpRequest request = requestBuilder.build();
        return Mono.fromCompletionStage(
                        () -> PROBE_CLIENT.sendAsync(
                                request, HttpResponse.BodyHandlers.discarding()))
                .timeout(PROBE_TIMEOUT.plusSeconds(1))
                .flatMap(response -> response.statusCode() == 200
                        ? Mono.empty()
                        : Mono.error(new IllegalStateException(
                                "A2A endpoint probe failed: HTTP " + response.statusCode())));
    }

    /**
     * 提取可读的失败原因；连接拒绝等异常 message 可能为空。
     */
    private static String describe(Throwable ex) {
        return StringUtils.hasText(ex.getMessage())
                ? ex.getMessage()
                : ex.getClass().getSimpleName();
    }

    /**
     * 只重试网络、发现、超时和 5xx；参数错误与其他 4xx 不重试。
     */
    private static boolean retryable(Throwable ex) {
        // 1. 参数/配置错误重试没有意义
        if (ex instanceof IllegalArgumentException) {
            return false;
        }

        // 2. HTTP 错误按状态码判断：5xx、408、429 可重试，其余 4xx 不重试
        if (ex instanceof A2AClientHTTPError httpError) {
            int code = httpError.getCode();
            return code >= 500 || code == 408 || code == 429;
        }

        // 3. 连接拒绝、超时、Nacos 发现失败等按瞬时故障处理
        return true;
    }

    /**
     * A2A JSON-RPC 调用的内部 token 拦截器。
     */
    private static final class InternalTokenInterceptor extends ClientCallInterceptor {

        // 服务端要求的内部 token
        private final String token;

        private InternalTokenInterceptor(String token) {
            this.token = token;
        }

        @Override
        public PayloadAndHeaders intercept(String method, Object payload,
                Map<String, String> headers, AgentCard agentCard, ClientCallContext context) {
            // 1. 保留 SDK 已有 header，再覆盖服务端 token
            Map<String, String> merged = new java.util.LinkedHashMap<>();
            if (headers != null) {
                merged.putAll(headers);
            }
            merged.put(CommonConstants.HEADER_INTERNAL_TOKEN, token);
            return new PayloadAndHeaders(payload, merged);
        }
    }
}
