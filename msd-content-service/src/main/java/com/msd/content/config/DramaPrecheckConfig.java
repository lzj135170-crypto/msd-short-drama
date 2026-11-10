package com.msd.content.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 创作者 AI 预检客户端配置。
 *
 * <p>预检 ChatClient 不挂记忆和 RAG Advisor，避免创作者文案进入用户会话。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "msd.ai.precheck", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DramaPrecheckProperties.class)
public class DramaPrecheckConfig {

    /**
     * 预检专用 ChatClient，使用默认 DeepSeek 模型。
     */
    @Bean("precheckChatClient")
    public ChatClient precheckChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.clone().build();
    }
}
