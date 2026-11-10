package com.msd.agent.tool;

import com.msd.agent.client.AgentRagClient;
import com.msd.agent.client.RagSearchRequest;
import com.msd.agent.client.dto.AgentRagViews.RagRecommendView;
import com.msd.agent.client.dto.AgentRagViews.RagSourceView;
import com.msd.common.result.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * search_drama_rag 工具的输出和槽位拼接测试。
 */
class RagSearchToolsTest {

    private final AgentRagClient ragClient = mock(AgentRagClient.class);
    private final RagSearchTools tools = new RagSearchTools(ragClient);

    /**
     * 槽位拼接到 query，返回回答和标准卡片。
     */
    @Test
    void returnsAnswerAndCards() {
        when(ragClient.recommend(any(), isNull())).thenReturn(Result.ok(new RagRecommendView(
                "推荐两部先婚后爱。", "先婚后爱", List.of(
                new RagSourceView(5001L, "隐婚总裁的甜妻", "先婚后爱", "", "甜宠; 先婚后爱", 0.9),
                new RagSourceView(5004L, "闪婚老公是首富", "先婚后爱", "", "闪婚", 0.8)),
                false, List.of(), 1200, "req-1")));

        String output = tools.searchDramaRag("有没有先婚后爱的剧", "甜宠", null, null, true);

        assertThat(output)
                .contains("[ANSWER] 推荐两部先婚后爱。")
                .contains("[DRAMA_CARD] dramaId=5001; title=隐婚总裁的甜妻")
                .doesNotContain("甜宠; 先婚后爱")
                .contains("[DRAMA_CARD] dramaId=5004");
        ArgumentCaptor<RagSearchRequest> captor = ArgumentCaptor.forClass(RagSearchRequest.class);
        verify(ragClient).recommend(captor.capture(), isNull());
        assertThat(captor.getValue().query())
                .contains("有没有先婚后爱的剧")
                .contains("甜宠")
                .contains("已完结");
    }

    /**
     * 无结果时明确标记。
     */
    @Test
    void marksNoResult() {
        when(ragClient.recommend(any(), isNull())).thenReturn(Result.ok(
                new RagRecommendView("抱歉，暂时没有找到符合你描述的短剧。", null,
                        List.of(), false, List.of(), 500, "req-1")));

        String output = tools.searchDramaRag("不存在的神话剧", null, null, null, null);

        assertThat(output).contains("[NO_RESULT]");
    }

    /**
     * 下游异常转成降级标记，不抛出。
     */
    @Test
    void degradesOnClientFailure() {
        when(ragClient.recommend(any(), isNull())).thenThrow(new IllegalStateException("down"));

        assertThat(tools.searchDramaRag("甜宠剧", null, null, null, null))
                .contains("[DEGRADED]");
    }
}
