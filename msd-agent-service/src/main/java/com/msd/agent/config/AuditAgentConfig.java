package com.msd.agent.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.msd.agent.runner.AuditAgentCardRegistrar;
import com.msd.agent.support.AuditPromptLoader;
import com.msd.agent.support.AuditPromptLoader.AuditPrompt;
import com.msd.agent.tool.DramaTools;
import com.msd.agent.tool.SpringAiToolBridge;
import com.msd.agent.tool.ToolGuard;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.TransportProtocol;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.core.model.Model;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryTransportProperties;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * audit profile 的 A2A 服务配置。
 *
 * <p>该配置把 audit-agent 包装成标准 A2A Endpoint，并把 AgentCard 注册到 Nacos。
 * 只在 {@code agentscope.a2a.server.enabled=true} 且模型可用时生效。</p>
 */
@Configuration
@Profile("audit")
@Conditional(AgentModelConfiguredCondition.class)
@ConditionalOnProperty(prefix = "agentscope.a2a.server", name = "enabled", havingValue = "true")
public class AuditAgentConfig {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AuditAgentConfig.class);

    /** audit-agent 只暴露审核需要的两个只读工具。 */
    private static final Set<String> AUDIT_TOOL_NAMES =
            Set.of("get_drama_detail", "list_drama_episodes");

    /**
     * 创建 audit 专用 Toolkit，避免把首页查询等无关工具暴露给审核 Agent。
     */
    @Bean
    public Toolkit auditToolkit(DramaTools dramaTools, ToolGuard toolGuard) {
        // 1. 复用 Spring AI 注解桥接和统一沙箱，再按审核白名单裁剪
        List<AgentTool> tools = toolGuard.filterAndValidate(
                SpringAiToolBridge.adapt(dramaTools, toolGuard), AUDIT_TOOL_NAMES);

        // 2. 注册到独立 Toolkit
        Toolkit toolkit = new Toolkit();
        tools.forEach(toolkit::registerAgentTool);
        log.info("Audit A2A tools registered: {}",
                tools.stream().map(AgentTool::getName).toList());
        return toolkit;
    }

    /**
     * 从 audit-agent.md 构建 ReActAgent.Builder。
     *
     * <p>A2A Server 会为每个请求构建新的 ReActAgent，保证并发请求不会共享会话状态。</p>
     */
    @Bean
    public ReActAgent.Builder auditAgentBuilder(Model model,
            @Qualifier("auditToolkit") Toolkit auditToolkit,
            AuditPromptLoader promptLoader) {
        AuditPrompt prompt = promptLoader.load();
        return ReActAgent.builder()
                .name("audit-agent")
                .description(prompt.description())
                .sysPrompt(prompt.body())
                .model(model)
                .toolkit(auditToolkit)
                .maxIters(6);
    }

    /**
     * A2A 请求处理线程池；使用 daemon 线程，避免进程退出时被非 daemon 线程拖住。
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService auditA2aExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "audit-a2a-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 创建 A2A Server；JSON-RPC 控制器由 AgentScope starter 自动装配。
     */
    @Bean
    public AgentScopeA2aServer auditA2aServer(ReActAgent.Builder auditAgentBuilder,
            ExecutorService auditA2aExecutor,
            @Value("${msd.ai.a2a.audit.name:audit-agent}") String agentName,
            @Value("${msd.ai.a2a.audit.host:127.0.0.1}") String host,
            @Value("${msd.ai.a2a.audit.port:${server.port:8106}}") int port,
            @Value("${msd.ai.a2a.audit.path:/}") String path) {
        // 1. 能力描述会同时出现在 /.well-known/agent-card.json 和 Nacos AgentCard 中
        String endpointPath = path.startsWith("/") ? path : "/" + path;
        String endpointUrl = "http://" + host + ":" + port + endpointPath;
        ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder()
                .name(agentName)
                .description("码上短剧内容合规审核 Agent，检查敏感、低俗、侵权和虚假宣传风险")
                .url(endpointUrl)
                .version("1.0.0")
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("content-audit")
                        .name("内容合规审核")
                        .description("审核标题、简介、评论的敏感、低俗、侵权与虚假宣传风险")
                        .tags(List.of("audit", "compliance"))
                        .inputModes(List.of("text"))
                        .outputModes(List.of("text"))
                        .build()))
                .build();

        // 2. 显式声明 JSON-RPC 的对外地址，供 AgentCard 和 Nacos Endpoint 共用
        TransportProperties transport = TransportProperties
                .builder(TransportProtocol.JSONRPC.asString())
                .host(host)
                .port(port)
                .path(path)
                .build();
        DeploymentProperties deployment = new DeploymentProperties.Builder()
                .host(host)
                .port(port)
                .path(path)
                .build();

        log.info("Audit A2A server: name={}, endpoint={}", agentName, endpointUrl);
        return AgentScopeA2aServer.builder(auditAgentBuilder)
                .agentCard(agentCard)
                .withTransport(transport)
                .deploymentProperties(deployment)
                .executor(auditA2aExecutor)
                .build();
    }

    /**
     * Nacos AI Service 客户端；复用给 AgentCard 注册器，进程退出时关闭。
     */
    @Bean(destroyMethod = "shutdown")
    public AiService a2aAiService(
            @Value("${msd.ai.a2a.nacos.server-addr:127.0.0.1:8848}") String serverAddr,
            @Value("${msd.ai.a2a.nacos.namespace:mashang-duanju-dev}") String namespace,
            @Value("${msd.ai.a2a.nacos.username:}") String username,
            @Value("${msd.ai.a2a.nacos.password:}") String password) throws NacosException {
        // 1. 服务地址必填
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        // 2. 命名空间和账号密码按配置可选填充
        if (StringUtils.hasText(namespace)) {
            properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        }
        if (StringUtils.hasText(username)) {
            properties.setProperty(PropertyKeyConst.USERNAME, username);
        }
        if (StringUtils.hasText(password)) {
            properties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        log.info("Nacos AI Service for A2A registry: serverAddr={}, namespace={}",
                serverAddr, namespace);
        return AiFactory.createAiService(properties);
    }

    /**
     * AgentScope 的 Nacos A2A 注册器。
     */
    @Bean
    public NacosA2aRegistry nacosA2aRegistry(AiService a2aAiService) {
        return new NacosA2aRegistry(a2aAiService);
    }

    /**
     * 注册参数：AgentCard 作为最新版本发布，并登记 JSON-RPC Endpoint。
     */
    @Bean
    public NacosA2aRegistryProperties auditRegistryProperties(
            @Value("${msd.ai.a2a.audit.host:127.0.0.1}") String host,
            @Value("${msd.ai.a2a.audit.port:${server.port:8106}}") int port,
            @Value("${msd.ai.a2a.audit.path:/}") String path) {
        // 1. 发布为最新版本，并登记 Endpoint
        NacosA2aRegistryProperties properties = NacosA2aRegistryProperties.builder()
                .setAsLatest(true)
                .enabledRegisterEndpoint(true)
                .build();
        // 2. 登记 JSON-RPC 传输地址
        properties.addTransport(NacosA2aRegistryTransportProperties.builder()
                .transport(TransportProtocol.JSONRPC.asString())
                .host(host)
                .port(port)
                .path(path)
                .build());
        return properties;
    }

    /**
     * 启动监听器：Web 服务就绪后把 AgentCard 注册到 Nacos。
     */
    @Bean
    public AuditAgentCardRegistrar auditAgentCardRegistrar(AgentScopeA2aServer auditA2aServer,
            NacosA2aRegistry nacosA2aRegistry,
            NacosA2aRegistryProperties auditRegistryProperties) {
        return new AuditAgentCardRegistrar(
                auditA2aServer, nacosA2aRegistry, auditRegistryProperties);
    }
}
