package com.msd.content.memory;

import com.msd.content.companion.CompanionLightModel;
import com.msd.content.config.CompanionProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 带历史摘要的会话记忆。
 *
 * <p>最近 {@code maxMessages} 条消息保留原文，更早的消息用轻量模型合并成一段历史摘要。
 * Redis 保存摘要和完整归档，Redis 不可用时退化为单实例内存窗口，聊天继续可用。</p>
 */
public class SummarizingChatMemory implements ChatMemory {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(SummarizingChatMemory.class);
    /** 历史摘要在 Prompt 中的前缀。 */
    private static final String SUMMARY_PREFIX = "【历史对话摘要】\n";
    /** 跨实例压缩锁 Key 前缀。 */
    private static final String LOCK_PREFIX = "msd:companion:lock:";
    /** 降级标记：记忆归档或压缩失败。 */
    public static final String FLAG_MEMORY_SUMMARY = "memory_summary";

    // 最近消息窗口
    private final MessageWindowChatMemory window;
    // Redis 归档
    private final RedisChatMemoryRepository archive;
    // 轻量模型，用于历史摘要
    private final CompanionLightModel lightModel;
    // 伴看配置
    private final CompanionProperties properties;
    // Redis 客户端访问器，用于跨实例锁
    private final CompanionRedisAccessor redisAccessor;
    // 每个会话最近一次操作产生的降级标记
    private final Map<String, Set<String>> degradedFlags = new ConcurrentHashMap<>();

    /**
     * 创建窗口、归档和摘要依赖。
     */
    public SummarizingChatMemory(RedisChatMemoryRepository archive,
                                 CompanionLightModel lightModel,
                                 CompanionProperties properties,
                                 CompanionRedisAccessor redisAccessor) {
        this.archive = archive;
        this.lightModel = lightModel;
        this.properties = properties;
        this.redisAccessor = redisAccessor;
        int maxMessages = Math.max(properties.getMemory().getMaxMessages(), 1);
        this.window = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maxMessages)
                .build();
    }

    /**
     * 追加消息；达到窗口上限时压缩最早的消息。
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        // 1. 过滤空消息和系统消息，只归档对话内容
        List<Message> incoming = dialogueOnly(messages);
        if (conversationId == null || conversationId.isBlank() || incoming.isEmpty()) {
            return;
        }

        // 2. 跨实例压缩锁；抢不到锁时本次跳过压缩，只追加
        RedissonClient client = redisAccessor.getOrNull();
        RLock lock = null;
        boolean locked = false;
        try {
            if (client != null) {
                lock = client.getLock(LOCK_PREFIX + conversationId);
                try {
                    locked = lock.tryLock(
                            Math.max(properties.getMemory().getLockWaitMs(), 1),
                            Math.max(properties.getMemory().getLockLeaseMs(), 1),
                            TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            append(conversationId, incoming, locked);
        } finally {
            if (locked && lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 读取摘要和最近窗口消息。
     */
    @Override
    public List<Message> get(String conversationId) {
        // 1. Redis 不可用时退回单实例窗口
        StoredMemory stored = archive.load(conversationId);
        if (stored == null) {
            markDegraded(conversationId, FLAG_MEMORY_SUMMARY);
            return window.get(conversationId);
        }
        if (stored.messages().isEmpty() && stored.summary().isBlank()) {
            return window.get(conversationId);
        }

        // 2. 摘要作为 SystemMessage 放在窗口消息之前
        List<Message> result = new ArrayList<>();
        if (hasText(stored.summary())) {
            result.add(new SystemMessage(SUMMARY_PREFIX + stored.summary()));
        }
        result.addAll(tail(fromStored(stored.messages()),
                Math.max(properties.getMemory().getMaxMessages(), 1)));
        return List.copyOf(result);
    }

    /**
     * 清理窗口和归档。
     */
    @Override
    public void clear(String conversationId) {
        archive.delete(conversationId);
        window.clear(conversationId);
        degradedFlags.remove(conversationId);
    }

    /**
     * 读取并清除最近一次操作产生的降级标记。
     */
    public List<String> drainDegradedFlags(String conversationId) {
        Set<String> flags = degradedFlags.remove(conversationId);
        return flags == null ? List.of() : List.copyOf(flags);
    }

    /**
     * 追加消息；locked 为 true 时才允许调用模型压缩。
     */
    private void append(String conversationId, List<Message> incoming, boolean locked) {
        // 1. 读取归档；Redis 不可用时只更新本地窗口
        StoredMemory stored = archive.load(conversationId);
        if (stored == null) {
            markDegraded(conversationId, FLAG_MEMORY_SUMMARY);
            window.add(conversationId, incoming);
            return;
        }

        // 2. 归档消息加新消息，去掉与最后一条重复的消息
        List<Message> all = new ArrayList<>(fromStored(stored.messages()));
        List<Message> effective = new ArrayList<>(incoming);
        if (!all.isEmpty() && !effective.isEmpty()
                && sameMessage(all.get(all.size() - 1), effective.get(0))) {
            effective.remove(0);
        }
        all.addAll(effective);

        // 3. 溢出消息攒够一批且拿到锁时才压缩，避免只拿一条消息生成摘要
        String summary = stored.summary();
        int maxMessages = Math.max(properties.getMemory().getMaxMessages(), 1);
        int batch = Math.max(properties.getMemory().getCompressionBatch(), 1);
        if (locked && all.size() - maxMessages >= batch) {
            int overflow = all.size() - maxMessages;
            String newSummary = summarize(summary, all.subList(0, overflow));
            if (newSummary != null) {
                summary = newSummary;
            } else {
                markDegraded(conversationId, FLAG_MEMORY_SUMMARY);
            }
            all = new ArrayList<>(all.subList(overflow, all.size()));
            dropLeadingAssistant(all);
        }

        // 4. 保存归档并同步本地窗口
        if (!archive.save(conversationId, summary, all)) {
            markDegraded(conversationId, FLAG_MEMORY_SUMMARY);
        }
        syncWindow(conversationId, summary, all);
    }

    /**
     * 用轻量模型合并旧摘要和溢出的对话，失败返回 null。
     */
    private String summarize(String oldSummary, List<Message> oldMessages) {
        if (!lightModel.isAvailable()) {
            return null;
        }
        int maxChars = Math.max(properties.getMemory().getMaxSummaryChars(), 1);
        int messageChars = Math.max(properties.getMemory().getCompressMessageChars(), 1);
        String systemPrompt = "你是短剧伴看会话的历史摘要生成器，只做压缩，不做创作。"
                + "把【已有摘要】和【新增对话】合并成一段不超过 " + maxChars + " 字的中文要点。"
                + "只保留用户问过的问题、助手给出的结论、用户偏好和尚未解决的疑问。"
                + "只使用原文中出现的人名、剧名和情节；不得推测、扩写，"
                + "不得引入任何新人物、新剧情，不得提及当前集之后的剧情。"
                + "用“用户/助手”作主语，不要写成故事。只输出摘要正文。";
        String userPrompt = "【已有摘要】\n"
                + (hasText(oldSummary) ? oldSummary : "（无）")
                + "\n\n【新增对话】\n"
                + transcript(oldMessages, messageChars);
        String text = lightModel.complete(systemPrompt, userPrompt);
        if (!hasText(text)) {
            return null;
        }
        return truncate(text.trim(), maxChars);
    }

    /**
     * 把本地窗口重置为摘要和最近消息，避免重复追加。
     */
    private void syncWindow(String conversationId, String summary, List<Message> all) {
        List<Message> windowMessages = new ArrayList<>();
        if (hasText(summary)) {
            windowMessages.add(new SystemMessage(SUMMARY_PREFIX + summary));
        }
        windowMessages.addAll(tail(all, Math.max(properties.getMemory().getMaxMessages(), 1)));
        window.clear(conversationId);
        if (!windowMessages.isEmpty()) {
            window.add(conversationId, windowMessages);
        }
    }

    /**
     * 只保留用户和助手消息。
     */
    private List<Message> dialogueOnly(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message == null || message.getMessageType() == null) {
                continue;
            }
            if (message.getMessageType() == MessageType.USER
                    || message.getMessageType() == MessageType.ASSISTANT) {
                result.add(message);
            }
        }
        return result;
    }

    /**
     * 归档消息还原为 Spring AI 消息。
     */
    private List<Message> fromStored(List<StoredMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>(messages.size());
        for (StoredMessage message : messages) {
            if (StoredMessage.TYPE_ASSISTANT.equals(message.type())) {
                result.add(new AssistantMessage(message.text()));
            } else {
                result.add(new UserMessage(message.text()));
            }
        }
        return result;
    }

    /**
     * 压缩提示中的对话文本，单条消息按配置截断。
     */
    private String transcript(List<Message> messages, int messageChars) {
        if (messages == null || messages.isEmpty()) {
            return "（无）";
        }
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            String role = message.getMessageType() == MessageType.USER ? "用户：" : "助手：";
            text.append(role)
                    .append(truncate(message.getText(), messageChars))
                    .append('\n');
        }
        return text.toString().trim();
    }

    /**
     * 判断两条消息角色和正文是否相同。
     */
    private boolean sameMessage(Message left, Message right) {
        return left != null && right != null
                && left.getMessageType() == right.getMessageType()
                && Objects.equals(left.getText(), right.getText());
    }

    /**
     * 去掉开头的助手消息，保证窗口从用户消息开始。
     */
    private void dropLeadingAssistant(List<Message> messages) {
        while (!messages.isEmpty()
                && messages.get(0).getMessageType() == MessageType.ASSISTANT) {
            messages.remove(0);
        }
    }

    /**
     * 截取列表末尾的条数。
     */
    private List<Message> tail(List<Message> messages, int limit) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int from = Math.max(messages.size() - limit, 0);
        return new ArrayList<>(messages.subList(from, messages.size()));
    }

    /**
     * 记录会话级降级标记。
     */
    private void markDegraded(String conversationId, String flag) {
        degradedFlags.computeIfAbsent(conversationId, key -> ConcurrentHashMap.newKeySet())
                .add(flag);
    }

    /**
     * 判断字符串是否包含非空白字符。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 按字符数截断文本。
     */
    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
