package com.msd.agent.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * AgentScope 启用条件：显式开启且存在可用的 DeepSeek API Key。
 */
public class AgentModelConfiguredCondition implements Condition {

    /**
     * 同时检查开关和 API Key，避免缺少密钥时 AgentScope Bean 创建失败拖垮服务启动。
     */
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // 1. 开关默认开启，可通过 msd.ai.agent.enabled=false 关闭
        Environment environment = context.getEnvironment();
        boolean enabled = environment.getProperty("msd.ai.agent.enabled", Boolean.class, Boolean.TRUE);
        if (!enabled) {
            return false;
        }

        // 2. Nacos 的 msd.ai.api-key 优先，其次读取进程环境变量
        String apiKey = environment.getProperty("msd.ai.api-key");
        if (!StringUtils.hasText(apiKey)) {
            apiKey = environment.getProperty("DEEPSEEK_API_KEY");
        }
        if (!StringUtils.hasText(apiKey)) {
            apiKey = System.getenv("DEEPSEEK_API_KEY");
        }
        return StringUtils.hasText(apiKey);
    }
}
