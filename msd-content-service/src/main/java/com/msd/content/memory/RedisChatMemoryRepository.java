package com.msd.content.memory;

import com.msd.content.config.CompanionProperties;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI 伴看会话记忆的 Redis 归档。
 *
 * <p>Key 为 {@code msd:companion:memory:{conversationId}}，JSON 保存历史摘要和最近消息，
 * TTL 默认 7 天。Redis 不可用时所有方法 fail-open，不抛异常。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisChatMemoryRepository {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);
    /** Key 前缀。 */
    private static final String KEY_PREFIX = "msd:companion:memory:";

    // Redis 客户端访问器
    private final CompanionRedisAccessor accessor;
    // JSON 序列化器
    private final JsonMapper jsonMapper;
    // 伴看配置
    private final CompanionProperties properties;

    /**
     * 注入 Redis 访问器、JSON 序列化器和伴看配置。
     */
    public RedisChatMemoryRepository(CompanionRedisAccessor accessor,
                                     JsonMapper jsonMapper,
                                     CompanionProperties properties) {
        this.accessor = accessor;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    /**
     * 读取会话记忆；Redis 不可用或数据损坏时返回 null。
     */
    public StoredMemory load(String conversationId) {
        RedissonClient client = accessor.getOrNull();
        if (client == null) {
            return null;
        }
        try {
            // 1. 用 StringCodec 读纯文本 JSON，避免 Redisson 默认编解码差异
            RBucket<String> bucket = client.getBucket(key(conversationId), StringCodec.INSTANCE);
            String json = bucket.get();
            if (json == null || json.isBlank()) {
                return StoredMemory.empty();
            }
            // 2. 反序列化失败按无记忆处理，不阻断聊天
            return jsonMapper.readValue(json, StoredMemory.class);
        } catch (Exception e) {
            log.warn("companion memory load failed: conversationId={}, error={}",
                    conversationId, rootMessage(e));
            return null;
        }
    }

    /**
     * 保存会话记忆，返回是否写入成功。
     */
    public boolean save(String conversationId, String summary, List<Message> messages) {
        RedissonClient client = accessor.getOrNull();
        if (client == null) {
            return false;
        }
        try {
            // 1. 只归档用户和助手消息，系统摘要单独存字段
            List<StoredMessage> stored = toStoredMessages(messages);
            int maxArchived = Math.max(properties.getMemory().getMaxArchivedMessages(), 1);
            if (stored.size() > maxArchived) {
                stored = stored.subList(stored.size() - maxArchived, stored.size());
            }
            StoredMemory memory = new StoredMemory(summary, stored);

            // 2. 写入 JSON 并按配置设置 TTL
            String json = jsonMapper.writeValueAsString(memory);
            int ttlDays = Math.max(properties.getMemory().getTtlDays(), 1);
            client.<String>getBucket(key(conversationId), StringCodec.INSTANCE)
                    .set(json, ttlDays, TimeUnit.DAYS);
            return true;
        } catch (Exception e) {
            log.warn("companion memory save failed: conversationId={}, error={}",
                    conversationId, rootMessage(e));
            return false;
        }
    }

    /**
     * 删除会话记忆。
     */
    public void delete(String conversationId) {
        RedissonClient client = accessor.getOrNull();
        if (client == null) {
            return;
        }
        try {
            client.getBucket(key(conversationId), StringCodec.INSTANCE).delete();
        } catch (Exception e) {
            log.debug("companion memory delete failed: conversationId={}, error={}",
                    conversationId, rootMessage(e));
        }
    }

    /**
     * 会话 Key。
     */
    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    /**
     * 过滤并转换需要归档的消息。
     */
    private List<StoredMessage> toStoredMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<StoredMessage> stored = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message == null || message.getMessageType() == null) {
                continue;
            }
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (message.getMessageType() == MessageType.USER) {
                stored.add(new StoredMessage(StoredMessage.TYPE_USER, text));
            } else if (message.getMessageType() == MessageType.ASSISTANT) {
                stored.add(new StoredMessage(StoredMessage.TYPE_ASSISTANT, text));
            }
        }
        return stored;
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
