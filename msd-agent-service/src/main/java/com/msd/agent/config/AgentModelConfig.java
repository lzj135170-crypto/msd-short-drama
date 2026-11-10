package com.msd.agent.config;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

/**
 * AgentScope 模型配置。
 *
 * <p>Supervisor 与 audit A2A 服务共用同一个 DeepSeek 模型 Bean；
 * 这是两个 profile 唯一的模型来源，避免重复创建客户端。</p>
 */
@Configuration
@Conditional(AgentModelConfiguredCondition.class)
public class AgentModelConfig {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AgentModelConfig.class);

    /**
     * 创建 DeepSeek 模型实例。
     *
     * <p>AgentScope 与 Spring AI 的 ChatModel 没有官方适配器，这里使用 AgentScope
     * 原生 Provider；Spring AI 只负责工具注解与 Schema。</p>
     */
    @Bean
    @Lazy
    public Model supervisorModel(
            @Value("${msd.ai.agent.model:deepseek-flash}") String modelName,
            @Value("${msd.ai.agent.base-url:}") String baseUrl,
            @Value("${msd.ai.api-key:}") String apiKey) {
        // 1. Nacos 的 msd.ai.api-key 优先，其次取进程环境变量
        String resolvedKey = StringUtils.hasText(apiKey)
                ? apiKey
                : System.getenv("DEEPSEEK_API_KEY");

        // 2. 可选 base-url，便于接 OpenAI 兼容网关
        ModelCreationContext.Builder context = ModelCreationContext.builder().apiKey(resolvedKey);
        if (StringUtils.hasText(baseUrl)) {
            context.baseUrl(baseUrl);
        }

        // 3. deepseek: 前缀由 AgentScope DeepSeek Provider 解析
        Model model = ModelRegistry.resolve("deepseek:" + modelName, context.build());
        log.info("AgentScope model: deepseek:{}, baseUrl={}",
                modelName, StringUtils.hasText(baseUrl) ? baseUrl : "default");
        return model;
    }
}
