package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 查询改写测试。
 */
class LlmQueryTransformerTest {

    private static final String ORIGINAL = "有没有那种先婚后爱的甜宠剧";

    private final Executor directExecutor = Runnable::run;

    /**
     * 成功改写应保留 trace 上下文并写入 rewrittenQuery。
     */
    @Test
    void rewritesAndKeepsContext() {
        ChatClient chatClient = chatClientReturning("关键词：先婚后爱 甜宠。");
        LlmQueryTransformer transformer = transformer(chatClient);
        RagTrace trace = new RagTrace(ORIGINAL);

        Query result = transformer.transform(query(trace));

        assertThat(result.text()).isEqualTo("先婚后爱 甜宠");
        assertThat(trace.getRewrittenQuery()).isEqualTo("先婚后爱 甜宠");
        assertThat(trace.isRewriteFailed()).isFalse();
        assertThat(result.context().get(RagTrace.CONTEXT_KEY)).isSameAs(trace);
    }

    /**
     * 模型返回空字符串时回退原查询并标记降级。
     */
    @Test
    void fallsBackWhenRewriteIsBlank() {
        ChatClient chatClient = chatClientReturning("   ");
        LlmQueryTransformer transformer = transformer(chatClient);
        RagTrace trace = new RagTrace(ORIGINAL);

        Query result = transformer.transform(query(trace));

        assertThat(result.text()).isEqualTo(ORIGINAL);
        assertThat(trace.isRewriteFailed()).isTrue();
        assertThat(trace.getDegradedFlags()).containsExactly("rewrite");
    }

    /**
     * 模型异常时回退原查询并标记降级。
     */
    @Test
    void fallsBackWhenChatClientThrows() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenThrow(new RuntimeException("rewrite down"));
        LlmQueryTransformer transformer = transformer(chatClient);
        RagTrace trace = new RagTrace(ORIGINAL);

        Query result = transformer.transform(query(trace));

        assertThat(result.text()).isEqualTo(ORIGINAL);
        assertThat(trace.isRewriteFailed()).isTrue();
        assertThat(trace.getDegradedFlags()).containsExactly("rewrite");
    }

    /**
     * 关闭改写时直接透传，不调用模型。
     */
    @Test
    void bypassesWhenDisabled() {
        RagProperties properties = new RagProperties();
        properties.getRewrite().setEnabled(false);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        LlmQueryTransformer transformer = new LlmQueryTransformer(
                chatClient, properties, directExecutor);
        RagTrace trace = new RagTrace(ORIGINAL);
        Query query = query(trace);

        Query result = transformer.transform(query);

        assertThat(result).isSameAs(query);
        assertThat(trace.getRewrittenQuery()).isNull();
    }

    /**
     * 实验指定模型时改写调用应带模型参数。
     */
    @Test
    void usesExperimentModelForRewrite() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("先婚后爱 甜宠");
        LlmQueryTransformer transformer = transformer(chatClient);
        RagTrace trace = new RagTrace(ORIGINAL);
        trace.setRewriteModel("deepseek-chat");

        Query result = transformer.transform(query(trace));

        assertThat(result.text()).isEqualTo("先婚后爱 甜宠");
        verify(spec).options(any());
    }

    /**
     * 构造被测改写器。
     */
    private LlmQueryTransformer transformer(ChatClient chatClient) {
        RagProperties properties = new RagProperties();
        properties.getRewrite().setTimeoutMs(1_000);
        properties.getRewrite().setFailureBackoffMs(30_000);
        return new LlmQueryTransformer(chatClient, properties, directExecutor);
    }

    /**
     * 构造返回固定内容的 ChatClient。
     */
    private ChatClient chatClientReturning(String content) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(content);
        return chatClient;
    }

    /**
     * 构造带 trace 的查询。
     */
    private Query query(RagTrace trace) {
        return Query.builder()
                .text(ORIGINAL)
                .context(Map.of(RagTrace.CONTEXT_KEY, trace))
                .build();
    }
}
