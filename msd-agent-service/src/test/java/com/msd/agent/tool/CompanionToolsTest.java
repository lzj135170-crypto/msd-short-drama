package com.msd.agent.tool;

import com.msd.agent.client.AgentCompanionClient;
import com.msd.agent.client.dto.AgentCompanionViews.CompanionContextView;
import com.msd.agent.client.dto.AgentCompanionViews.ContextItem;
import com.msd.agent.client.dto.AgentCompanionViews.EpisodeSummaryView;
import com.msd.common.result.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 伴看工具文本格式和降级测试。
 */
class CompanionToolsTest {

    private final AgentCompanionClient client = mock(AgentCompanionClient.class);
    private final CompanionTools tools = new CompanionTools(client);

    /**
     * 摘要工具输出当前集、摘要、来源和防剧透提示。
     */
    @Test
    void formatsEpisodeSummary() {
        when(client.summary(6005L)).thenReturn(Result.ok(new EpisodeSummaryView(
                6005L, 5001L, 5, "第5集 告白", "隐婚总裁的甜妻",
                "顾沉舟在雨夜坦白心意。", "seed", false, List.of())));

        String text = tools.getEpisodeSummary(6005L);

        assertThat(text)
                .contains("[EPISODE] dramaId=5001 episodeId=6005 episodeNo=5 title=第5集 告白")
                .contains("[SUMMARY] 顾沉舟在雨夜坦白心意。")
                .contains("[SOURCE] seed")
                .contains("[NOTICE] 只能把摘要当作当前集事实；不要推断第6集及之后。");
    }

    /**
     * 摘要缺失时标记 SUMMARY_MISSING，不编造内容。
     */
    @Test
    void returnsSummaryMissing() {
        when(client.summary(6005L)).thenReturn(Result.ok(new EpisodeSummaryView(
                6005L, 5001L, 5, "第5集 告白", "隐婚总裁的甜妻",
                "", "missing", true, List.of("summary_model"))));

        String text = tools.getEpisodeSummary(6005L);

        assertThat(text)
                .contains("[SUMMARY_MISSING]")
                .contains("[DEGRADED] flags=summary_model")
                .doesNotContain("[SUMMARY] ");
    }

    /**
     * 剧集不可见时返回 NOT_FOUND。
     */
    @Test
    void returnsNotFoundWhenEpisodeInvisible() {
        when(client.summary(6005L)).thenReturn(Result.ok(null));

        assertThat(tools.getEpisodeSummary(6005L))
                .startsWith("[NOT_FOUND]");
    }

    /**
     * 远程异常转成 DEGRADED，不抛出。
     */
    @Test
    void returnsDegradedOnRemoteFailure() {
        when(client.summary(6005L)).thenThrow(new IllegalStateException("content down"));

        assertThat(tools.getEpisodeSummary(6005L))
                .startsWith("[DEGRADED] 分集摘要暂不可用");
    }

    /**
     * 非法 ID 不触发远程调用。
     */
    @Test
    void rejectsInvalidEpisodeId() {
        assertThat(tools.getEpisodeSummary(0L)).startsWith("[NOT_FOUND]");

        verify(client, never()).summary(any());
    }

    /**
     * 前情工具输出当前集、命中条数、逐条前情和防剧透提示。
     */
    @Test
    void formatsContext() {
        when(client.context(any())).thenReturn(Result.ok(new CompanionContextView(
                6005L, 5001L, 5,
                List.of(
                        new ContextItem(6003L, 3, "第3集 危机", "项目被截胡。", 0.9,
                                List.of("vector", "bm25")),
                        new ContextItem(6004L, 4, "第4集 反击", "苏晚当众反击。", 0.8,
                                List.of("vector"))),
                false, List.of(), 20)));

        String text = tools.searchDramaContext("项目被截胡", 6005L);

        assertThat(text)
                .contains("[CONTEXT] dramaId=5001 currentEpisodeId=6005 currentEpisodeNo=5 returned=2")
                .contains("[1] episodeId=6003 episodeNo=3 title=第3集 危机")
                .contains("摘要：项目被截胡。")
                .contains("[2] episodeId=6004 episodeNo=4 title=第4集 反击")
                .contains("[NOTICE] 以上仅为第5集之前的内容；不得推测后续剧情。");
    }

    /**
     * 正常空结果返回 CONTEXT_EMPTY。
     */
    @Test
    void returnsContextEmpty() {
        when(client.context(any())).thenReturn(Result.ok(new CompanionContextView(
                6002L, 5001L, 2, List.of(), false, List.of(), 5)));

        assertThat(tools.searchDramaContext("前情", 6002L))
                .startsWith("[CONTEXT_EMPTY]");
    }

    /**
     * 检索降级且无结果时同时标记 DEGRADED 和 CONTEXT_EMPTY。
     */
    @Test
    void returnsDegradedContext() {
        when(client.context(any())).thenReturn(Result.ok(new CompanionContextView(
                6005L, 5001L, 5, List.of(), true, List.of("vector", "bm25"), 5)));

        String text = tools.searchDramaContext("前情", 6005L);

        assertThat(text)
                .contains("[DEGRADED] flags=vector,bm25")
                .contains("[CONTEXT_EMPTY]");
    }

    /**
     * 前情查询为空或剧集 ID 非法时不触发远程调用。
     */
    @Test
    void rejectsInvalidContextInput() {
        assertThat(tools.searchDramaContext(" ", 6005L)).startsWith("[CONTEXT_EMPTY]");
        assertThat(tools.searchDramaContext("前情", 0L)).startsWith("[NOT_FOUND]");

        verify(client, never()).context(any());
    }
}
