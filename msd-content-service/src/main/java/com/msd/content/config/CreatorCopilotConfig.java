package com.msd.content.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 创作者 Copilot 客户端配置。
 *
 * <p>Builder 不挂记忆和 RAG Advisor，避免创作者素材进入用户会话。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "msd.ai.copilot", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CreatorCopilotProperties.class)
public class CreatorCopilotConfig {

    /**
     * Copilot 专用 ChatClient，使用默认 DeepSeek 模型。
     */
    @Bean("copilotChatClient")
    public ChatClient copilotChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.clone().build();
    }
}
