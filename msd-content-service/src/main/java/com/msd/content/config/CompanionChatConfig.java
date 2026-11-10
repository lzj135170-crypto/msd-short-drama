package com.msd.content.config;

import com.msd.content.companion.CompanionLightModel;
import com.msd.content.memory.CompanionRedisAccessor;
import com.msd.content.memory.RedisChatMemoryRepository;
import com.msd.content.memory.SummarizingChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 伴看聊天装配：摘要记忆和带记忆 Advisor 的 ChatClient。
 */
@Configuration
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionChatConfig {

    /**
     * 会话记忆：最近窗口 + Redis 历史摘要。
     */
    @Bean
    public SummarizingChatMemory companionChatMemory(RedisChatMemoryRepository repository,
                                                     CompanionLightModel lightModel,
                                                     CompanionProperties properties,
                                                     CompanionRedisAccessor redisAccessor) {
        return new SummarizingChatMemory(repository, lightModel, properties, redisAccessor);
    }

    /**
     * 伴看 ChatClient，只挂记忆 Advisor，不挂推荐 RAG Advisor。
     */
    @Bean("companionChatClient")
    public ChatClient companionChatClient(ChatClient.Builder chatClientBuilder,
                                          SummarizingChatMemory chatMemory) {
        return chatClientBuilder.clone()
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
