package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 多查询扩展测试。
 */
class LlmMultiQueryExpanderTest {

    /**
     * 未调用模型时，原查询和偏好查询按顺序去重。
     */
    @Test
    void addsPreferenceQueryWithoutCallingModel() {
        RagProperties properties = new RagProperties();
        properties.getExpansion().setEnabled(true);
        properties.getExpansion().setAdditionalQueries(0);
        properties.getExpansion().setIncludePreference(true);

        LlmMultiQueryExpander expander = new LlmMultiQueryExpander(
                mock(ChatClient.class), properties, Runnable::run);
        RagTrace trace = new RagTrace("先婚后爱");
        trace.setPreferenceQuery("甜宠 逆袭");
        Query query = new Query("先婚后爱", List.of(),
                Map.of(RagTrace.CONTEXT_KEY, trace));

        List<Query> expanded = expander.expand(query);

        assertThat(expanded).extracting(Query::text)
                .containsExactly("先婚后爱", "甜宠 逆袭");
        assertThat(trace.getExpandedQueries())
                .containsExactly("先婚后爱", "甜宠 逆袭");
    }

    /**
     * 关闭扩展时只返回原查询。
     */
    @Test
    void keepsOriginalWhenDisabled() {
        RagProperties properties = new RagProperties();
        LlmMultiQueryExpander expander = new LlmMultiQueryExpander(
                mock(ChatClient.class), properties, Runnable::run);

        List<Query> expanded = expander.expand(new Query("q"));

        assertThat(expanded).extracting(Query::text).containsExactly("q");
    }
}
