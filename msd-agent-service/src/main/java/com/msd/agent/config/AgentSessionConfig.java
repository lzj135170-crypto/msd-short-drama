package com.msd.agent.config;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

/**
 * AgentScope Redis 会话状态配置。
 *
 * <p>仅 Supervisor 使用；audit profile 不依赖 Redis。</p>
 */
@Configuration
@Profile("!audit")
@Conditional(AgentModelConfiguredCondition.class)
public class AgentSessionConfig {

    /**
     * 使用 RedissonClient 创建 Redis 状态存储，key 前缀固定为 mashang:agent:session:。
     */
    @Bean
    @Lazy
    public AgentStateStore agentStateStore(RedissonClient redissonClient,
            @Value("${msd.ai.agent.session-key-prefix:mashang:agent:session:}") String keyPrefix) {
        return RedisAgentStateStore.builder()
                .redissonClient(redissonClient)
                .keyPrefix(keyPrefix)
                .build();
    }
}
