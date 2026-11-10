package com.msd.agent.config;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * Supervisor 侧的 A2A 发现配置。
 *
 * <p>只用 Nacos 解析 AgentCard，不注册自身；懒加载保证 Nacos 暂不可用时
 * Supervisor 仍能启动，首次远程审核失败后再降级。</p>
 */
@Configuration
@Profile("!audit")
public class RemoteAuditConfig {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(RemoteAuditConfig.class);

    /**
     * 创建仅供 AgentCard 发现使用的 Nacos AI Service 客户端。
     */
    @Bean(destroyMethod = "shutdown")
    @Lazy
    public AiService remoteA2aAiService(
            @Value("${msd.ai.a2a.nacos.server-addr:127.0.0.1:8848}") String serverAddr,
            @Value("${msd.ai.a2a.nacos.namespace:mashang-duanju-dev}") String namespace,
            @Value("${msd.ai.a2a.nacos.username:}") String username,
            @Value("${msd.ai.a2a.nacos.password:}") String password) throws NacosException {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        if (StringUtils.hasText(namespace)) {
            properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        }
        if (StringUtils.hasText(username)) {
            properties.setProperty(PropertyKeyConst.USERNAME, username);
        }
        if (StringUtils.hasText(password)) {
            properties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        log.info("Nacos AI Service for A2A discovery: serverAddr={}, namespace={}",
                serverAddr, namespace);
        return AiFactory.createAiService(properties);
    }

    /**
     * 动态 AgentCard 解析器；Nacos 卡片变化会通过订阅自动刷新缓存。
     */
    @Bean
    @Lazy
    public NacosAgentCardResolver auditAgentCardResolver(AiService remoteA2aAiService) {
        return new NacosAgentCardResolver(remoteA2aAiService);
    }
}
