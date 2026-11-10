package com.msd.content.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评论课代表 ChatClient，不挂记忆和 RAG Advisor。
 */
@Configuration
@ConditionalOnProperty(prefix = "msd.ai.comment-summary", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CommentSummaryConfig {

    /**
     * 评论摘要专用客户端，使用默认 DeepSeek 模型。
     */
    @Bean("commentSummaryChatClient")
    public ChatClient commentSummaryChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.clone().build();
    }
}
