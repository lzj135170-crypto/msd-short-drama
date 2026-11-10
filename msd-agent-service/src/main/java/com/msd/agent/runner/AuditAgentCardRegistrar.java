package com.msd.agent.runner;

import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * audit 进程启动后把 AgentCard 和 JSON-RPC Endpoint 注册到 Nacos AI Service。
 *
 * <p>使用 ApplicationRunner 而不是 A2A Server 自带的 AgentRegistry 回调，确保注册逻辑
 * 显式可见，并且可以在 Nacos 短暂不可用时做有限重试。</p>
 */
public class AuditAgentCardRegistrar implements ApplicationRunner {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AuditAgentCardRegistrar.class);

    /** 启动阶段最多尝试注册次数。 */
    private static final int MAX_ATTEMPTS = 3;

    // 提供待注册 AgentCard 的 A2A 服务端
    private final AgentScopeA2aServer a2aServer;
    // Nacos A2A 注册器
    private final NacosA2aRegistry registry;
    // 注册参数：版本策略与 Endpoint 信息
    private final NacosA2aRegistryProperties registryProperties;

    /**
     * 注入 A2A Server、Nacos 注册器和注册参数。
     */
    public AuditAgentCardRegistrar(AgentScopeA2aServer a2aServer,
            NacosA2aRegistry registry,
            NacosA2aRegistryProperties registryProperties) {
        this.a2aServer = a2aServer;
        this.registry = registry;
        this.registryProperties = registryProperties;
    }

    /**
     * Spring Boot 启动完成后执行注册；最终失败会让进程启动失败，避免静默丢服务。
     */
    @Override
    public void run(ApplicationArguments args) {
        // 1. 取服务端已构建的 AgentCard
        AgentCard card = a2aServer.getAgentCard();
        RuntimeException lastError = null;
        // 2. 有限次重试注册，成功即返回
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
            try {
                registry.registerAgent(card, registryProperties);
                log.info("Registered A2A AgentCard to Nacos: name={}, version={}, url={}",
                        card.name(), card.version(), card.url());
                return;
            } catch (RuntimeException ex) {
                lastError = ex;
                log.warn("Register A2A AgentCard failed (attempt {}/{}): {}",
                        attempt, MAX_ATTEMPTS, ex.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                }
            }
        }
        // 3. 重试耗尽后抛出，让启动失败而不是静默丢失服务
        throw new IllegalStateException(
                "Failed to register A2A AgentCard to Nacos after " + MAX_ATTEMPTS + " attempts",
                lastError);
    }

    /**
     * 简单线性退避，避免 Nacos 刚启动时连续打满注册接口。
     */
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying Nacos registration", ex);
        }
    }
}
