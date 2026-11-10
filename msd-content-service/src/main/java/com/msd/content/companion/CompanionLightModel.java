package com.msd.content.companion;

import com.msd.content.config.CompanionProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * AI 伴看使用的轻量模型客户端（默认 qwen-turbo）。
 *
 * <p>不挂记忆和 RAG Advisor，只用于摘要生成和历史对话压缩。
 * 没有有效 API Key 时保持不可用，调用方直接降级，不等待 401。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionLightModel {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CompanionLightModel.class);
    /** 未配置 Key 时的占位值前缀。 */
    private static final String PLACEHOLDER_KEY_PREFIX = "sk-not-configured";

    // 轻量模型名，写入摘要回写请求
    private final String modelName;
    // 可用的 ChatClient，未配置或初始化失败时为 null
    private final ChatClient chatClient;
    // 不可用原因，用于日志和排查
    private final String unavailableReason;

    /**
     * 根据配置和 OpenAI 兼容连接信息构建轻量客户端。
     */
    public CompanionLightModel(CompanionProperties properties, Environment environment) {
        CompanionProperties.LightModel config = properties.getLightModel();
        this.modelName = config.getModel();
        this.chatClient = createChatClient(config, environment);
        this.unavailableReason = chatClient == null ? "light model not configured" : null;
        if (chatClient == null) {
            log.warn("companion light model disabled: {}", unavailableReason);
        } else {
            log.info("companion light model ready: model={}, timeoutMs={}",
                    modelName, config.getTimeoutMs());
        }
    }

    /**
     * 轻量模型是否可用。
     */
    public boolean isAvailable() {
        return chatClient != null;
    }

    /**
     * 当前使用的模型名，写入摘要回写请求方便排查。
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 单轮生成，失败或为空时返回 null，由调用方降级。
     */
    public String complete(String systemPrompt, String userPrompt) {
        if (chatClient == null) {
            return null;
        }
        try {
            // 1. 单轮调用，不带记忆和工具
            String text = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            // 2. 空文本视为失败，避免把空摘要写回数据库
            return text == null || text.isBlank() ? null : text.trim();
        } catch (Exception e) {
            log.warn("companion light model call failed: model={}, error={}",
                    modelName, rootMessage(e));
            return null;
        }
    }

    /**
     * 构建 ChatClient，Key 无效时返回 null。
     */
    private ChatClient createChatClient(CompanionProperties.LightModel config,
                                        Environment environment) {
        // 1. 解析接口地址和 Key，优先使用伴看配置，否则复用 OpenAI 兼容配置
        String baseUrl = hasText(config.getBaseUrl())
                ? config.getBaseUrl().trim()
                : environment.getProperty("spring.ai.openai.base-url",
                "https://dashscope.aliyuncs.com/compatible-mode/v1");
        String apiKey = hasText(config.getApiKey())
                ? config.getApiKey().trim()
                : environment.getProperty("spring.ai.openai.api-key", "");
        if (!hasText(apiKey) || apiKey.startsWith(PLACEHOLDER_KEY_PREFIX)) {
            return null;
        }

        try {
            // 2. 手工构建同步和异步客户端，避免依赖 OpenAI chat 自动装配
            Duration timeout = Duration.ofMillis(Math.max(config.getTimeoutMs(), 1_000));
            OpenAIClient client = OpenAiSetup.setupSyncClient(
                    baseUrl, apiKey, null, null, null, null, false, false,
                    config.getModel(), timeout, config.getMaxRetries(), null, Map.of(),
                    ObservationRegistry.NOOP, Metrics.globalRegistry, List.of());
            OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
                    baseUrl, apiKey, null, null, null, null, false, false,
                    config.getModel(), timeout, config.getMaxRetries(), null, Map.of(),
                    ObservationRegistry.NOOP, Metrics.globalRegistry, List.of());

            // 3. 固定低温度和小输出，减少摘要发散
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .model(config.getModel())
                    .temperature(config.getTemperature())
                    .maxTokens(config.getMaxTokens())
                    .timeout(timeout)
                    .maxRetries(config.getMaxRetries())
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiClient(client)
                    .openAiClientAsync(asyncClient)
                    .options(options)
                    .build();
            return ChatClient.create(model);
        } catch (Exception e) {
            log.warn("companion light model init failed: model={}, error={}",
                    config.getModel(), rootMessage(e));
            return null;
        }
    }

    /**
     * 判断字符串是否包含非空白字符。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 取最内层异常信息。
     */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
