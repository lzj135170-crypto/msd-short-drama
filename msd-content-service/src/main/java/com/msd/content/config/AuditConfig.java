package com.msd.content.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 结构化审核客户端配置。
 *
 * <p>auditChatClient 不挂记忆和 RAG Advisor，避免审核文本写入用户会话。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "msd.ai.audit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfig {

    /**
     * 审核专用 ChatClient，使用默认 DeepSeek 模型，不挂任何 Advisor。
     */
    @Bean("auditChatClient")
    public ChatClient auditChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.clone().build();
    }
}
