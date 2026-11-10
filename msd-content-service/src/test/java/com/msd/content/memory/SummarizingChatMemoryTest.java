package com.msd.content.memory;

import com.msd.content.companion.CompanionLightModel;
import com.msd.content.config.CompanionProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话记忆压缩和 Redis 降级测试。
 */
class SummarizingChatMemoryTest {

    private final RedisChatMemoryRepository archive = mock(RedisChatMemoryRepository.class);
    private final CompanionLightModel lightModel = mock(CompanionLightModel.class);
    private final CompanionRedisAccessor redisAccessor = mock(CompanionRedisAccessor.class);
    private final CompanionProperties properties = new CompanionProperties();

    private final SummarizingChatMemory memory = new SummarizingChatMemory(
            archive, lightModel, properties, redisAccessor);

    /**
     * 窗口内消息不触发压缩，原摘要保持不变。
     */
    @Test
    void doesNotCompressWithinWindow() {
        RedissonClient client = redisson();
        when(redisAccessor.getOrNull()).thenReturn(client);
        when(archive.load("c1")).thenReturn(stored(18, "旧摘要"));
        when(archive.save(eq("c1"), anyString(), any())).thenReturn(true);

        memory.add("c1", List.of(new UserMessage("u19"), new AssistantMessage("a20")));

        verify(lightModel, never()).complete(anyString(), anyString());
        ArgumentCaptor<List<Message>> messages = messagesCaptor();
        verify(archive).save(eq("c1"), eq("旧摘要"), messages.capture());
        assertThat(messages.getValue()).hasSize(20);
    }

    /**
     * 超过窗口时把最早的消息压缩成新摘要，只保留最近 20 条。
     */
    @Test
    void compressesWhenExceedingWindow() {
        RedissonClient client = redisson();
        when(redisAccessor.getOrNull()).thenReturn(client);
        when(archive.load("c1")).thenReturn(stored(22, "旧摘要"));
        when(lightModel.isAvailable()).thenReturn(true);
        when(lightModel.complete(anyString(), anyString())).thenReturn("合并后的新摘要");
        when(archive.save(eq("c1"), anyString(), any())).thenReturn(true);

        memory.add("c1", List.of(new UserMessage("u21"), new AssistantMessage("a22")));

        ArgumentCaptor<List<Message>> messages = messagesCaptor();
        verify(archive).save(eq("c1"), eq("合并后的新摘要"), messages.capture());
        assertThat(messages.getValue()).hasSize(20);
        assertThat(messages.getValue().get(0).getText()).isEqualTo("msg-4");
        assertThat(memory.drainDegradedFlags("c1")).isEmpty();
    }

    /**
     * 溢出消息不足一批时不调用模型，先把消息留在归档里。
     */
    @Test
    void doesNotCompressBelowBatch() {
        RedissonClient client = redisson();
        when(redisAccessor.getOrNull()).thenReturn(client);
        when(archive.load("c1")).thenReturn(stored(21, "旧摘要"));
        when(archive.save(eq("c1"), anyString(), any())).thenReturn(true);

        memory.add("c1", List.of(new UserMessage("u22"), new AssistantMessage("a23")));

        verify(lightModel, never()).complete(anyString(), anyString());
        ArgumentCaptor<List<Message>> messages = messagesCaptor();
        verify(archive).save(eq("c1"), eq("旧摘要"), messages.capture());
        assertThat(messages.getValue()).hasSize(23);
    }

    /**
     * 摘要失败时丢弃最早消息但保留旧摘要，窗口仍然有界。
     */
    @Test
    void keepsBoundedWhenSummarizeFails() {
        RedissonClient client = redisson();
        when(redisAccessor.getOrNull()).thenReturn(client);
        when(archive.load("c1")).thenReturn(stored(22, "旧摘要"));
        when(lightModel.isAvailable()).thenReturn(true);
        when(lightModel.complete(anyString(), anyString())).thenReturn(null);
        when(archive.save(eq("c1"), anyString(), any())).thenReturn(true);

        memory.add("c1", List.of(new UserMessage("u21"), new AssistantMessage("a22")));

        ArgumentCaptor<List<Message>> messages = messagesCaptor();
        verify(archive).save(eq("c1"), eq("旧摘要"), messages.capture());
        assertThat(messages.getValue()).hasSize(20);
        assertThat(memory.drainDegradedFlags("c1"))
                .containsExactly(SummarizingChatMemory.FLAG_MEMORY_SUMMARY);
    }

    /**
     * Redis 不可用时只更新本地窗口，不抛异常。
     */
    @Test
    void failsOpenWhenRedisUnavailable() {
        when(redisAccessor.getOrNull()).thenReturn(null);
        when(archive.load("c1")).thenReturn(null);

        memory.add("c1", List.of(new UserMessage("你好")));

        assertThat(memory.get("c1")).extracting(Message::getText).containsExactly("你好");
        assertThat(memory.drainDegradedFlags("c1"))
                .containsExactly(SummarizingChatMemory.FLAG_MEMORY_SUMMARY);
    }

    /**
     * 读取时把历史摘要作为 SystemMessage 放在最前面。
     */
    @Test
    void getReturnsSummaryBeforeMessages() {
        when(archive.load("c1")).thenReturn(new StoredMemory("历史摘要", List.of(
                new StoredMessage(StoredMessage.TYPE_USER, "之前的问题"),
                new StoredMessage(StoredMessage.TYPE_ASSISTANT, "之前的回答"))));

        List<Message> messages = memory.get("c1");

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).contains("历史摘要");
        assertThat(messages.get(1).getText()).isEqualTo("之前的问题");
        assertThat(messages.get(2).getText()).isEqualTo("之前的回答");
    }

    /**
     * 清理会话时同时删除 Redis 归档。
     */
    @Test
    void clearDeletesArchive() {
        memory.clear("c1");

        verify(archive).delete("c1");
    }

    /**
     * 构造可用的 Redisson 客户端和锁。
     */
    private RedissonClient redisson() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return client;
    }

    /**
     * 构造指定条数的归档消息。
     */
    private StoredMemory stored(int count, String summary) {
        List<StoredMessage> messages = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String type = index % 2 == 0
                    ? StoredMessage.TYPE_USER : StoredMessage.TYPE_ASSISTANT;
            messages.add(new StoredMessage(type, "msg-" + index));
        }
        return new StoredMemory(summary, messages);
    }

    /**
     * 归档消息列表的参数捕获器。
     */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<Message>> messagesCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
