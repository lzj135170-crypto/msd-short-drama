package com.msd.content.audit;

import com.msd.common.result.Result;
import com.msd.content.client.DramaTagClient;
import com.msd.content.config.RexAdvisoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Rex 审核建议的配置、字典缓存、失败退避和结果裁剪测试。
 */
class RexAdvisoryServiceTest {

    private final RexAdvisoryClient client = mock(RexAdvisoryClient.class);
    private final DramaTagClient tagClient = mock(DramaTagClient.class);
    private final RexAdvisoryProperties properties = new RexAdvisoryProperties();
    private final Executor executor = Runnable::run;
    private final RexAdvisoryService service =
            new RexAdvisoryService(client, properties, tagClient, executor);

    /**
     * 启用服务并准备标签字典。
     */
    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setFailureBackoffMs(60_000);
        properties.setTagCacheMs(300_000);
        properties.setMaxTagCandidates(30);
        properties.setDefaultTagCandidates(List.of("逆袭", "悬疑"));
        properties.setSentimentLabels(List.of("正向情感", "负向情感"));
        when(tagClient.tagDictionary()).thenReturn(Result.ok(List.of("悬疑", "甜宠")));
    }

    /**
     * 关闭开关时不调用任何下游。
     */
    @Test
    void disabledReturnsNull() throws Exception {
        properties.setEnabled(false);

        DramaReviewAdvisory advisory = service.analyzeAsync("雾城谜案", 5003L)
                .get(1, TimeUnit.SECONDS);

        assertThat(advisory).isNull();
        verifyNoInteractions(client, tagClient);
    }

    /**
     * 成功结果映射为持久化 DTO，标签候选来自 drama 字典。
     */
    @Test
    void mapsSuccessfulResponse() throws Exception {
        when(client.analyze(any())).thenReturn(success());

        DramaReviewAdvisory advisory = service.analyzeAsync("雾城谜案", 5003L)
                .get(1, TimeUnit.SECONDS);

        assertThat(advisory.sentiment().highNegative()).isTrue();
        assertThat(advisory.tags()).extracting(DramaTagSuggestion::name)
                .containsExactly("悬疑", "甜宠");
        ArgumentCaptor<RexAnalyzeRequest> captor = ArgumentCaptor.forClass(RexAnalyzeRequest.class);
        verify(client).analyze(captor.capture());
        assertThat(captor.getValue().requestId()).isEqualTo("5003");
        assertThat(captor.getValue().tagCandidates()).containsExactly("悬疑", "甜宠");
    }

    /**
     * 标签字典不可用时回退 Nacos 默认候选。
     */
    @Test
    void fallsBackToDefaultTags() throws Exception {
        when(tagClient.tagDictionary()).thenThrow(new RuntimeException("drama down"));
        when(client.analyze(any())).thenReturn(success());

        service.analyzeAsync("雾城谜案", 5003L).get(1, TimeUnit.SECONDS);

        ArgumentCaptor<RexAnalyzeRequest> captor = ArgumentCaptor.forClass(RexAnalyzeRequest.class);
        verify(client).analyze(captor.capture());
        assertThat(captor.getValue().tagCandidates()).containsExactly("逆袭", "悬疑");
    }

    /**
     * 标签字典缓存生效，第二次分析不重复调用 drama。
     */
    @Test
    void cachesTagDictionary() throws Exception {
        when(client.analyze(any())).thenReturn(success());

        service.analyzeAsync("雾城谜案", 5003L).get(1, TimeUnit.SECONDS);
        service.analyzeAsync("雾城谜案", 5004L).get(1, TimeUnit.SECONDS);

        verify(tagClient, times(1)).tagDictionary();
    }

    /**
     * 下游失败后进入退避，避免 sidecar 关闭时反复打点。
     */
    @Test
    void backsOffAfterFailure() throws Exception {
        when(client.analyze(any())).thenReturn(degradedResponse());

        DramaReviewAdvisory first = service.analyzeAsync("雾城谜案", 5003L)
                .get(1, TimeUnit.SECONDS);
        DramaReviewAdvisory second = service.analyzeAsync("雾城谜案", 5004L)
                .get(1, TimeUnit.SECONDS);

        assertThat(first.degraded()).isTrue();
        assertThat(second.degraded()).isTrue();
        assertThat(second.degradedFlags()).contains("rex_backoff");
        verify(client, times(1)).analyze(any());
    }

    /**
     * 客户端异常转为降级，不向审核链路抛出。
     */
    @Test
    void clientExceptionBecomesDegraded() throws Exception {
        when(client.analyze(any())).thenThrow(new RuntimeException("boom"));

        DramaReviewAdvisory advisory = service.analyzeAsync("雾城谜案", 5003L)
                .get(1, TimeUnit.SECONDS);

        assertThat(advisory.degraded()).isTrue();
        assertThat(advisory.degradedFlags()).contains("rex_unavailable");
    }

    /**
     * 标签数量、证据数量和证据长度都在写回前裁剪。
     */
    @Test
    void trimsAdvisoryPayload() throws Exception {
        when(tagClient.tagDictionary()).thenReturn(Result.ok(List.of(
                "标签0", "标签1", "标签2", "标签3", "标签4", "标签5", "标签6")));
        when(client.analyze(any())).thenReturn(oversizedResponse());

        DramaReviewAdvisory advisory = service.analyzeAsync("雾城谜案", 5003L)
                .get(1, TimeUnit.SECONDS);

        assertThat(advisory.tags()).hasSize(5);
        assertThat(advisory.sentiment().evidence()).hasSize(3);
        assertThat(advisory.tags()).allSatisfy(tag ->
                assertThat(tag.evidence()).allSatisfy(evidence ->
                        assertThat(evidence).hasSizeLessThanOrEqualTo(120)));
    }

    /**
     * await 超时取消任务并返回降级。
     */
    @Test
    void awaitTimeoutDegrades() {
        CompletableFuture<DramaReviewAdvisory> never = new CompletableFuture<>();

        DramaReviewAdvisory advisory = service.await(never, 10);

        assertThat(advisory.degraded()).isTrue();
        assertThat(advisory.degradedFlags()).contains("rex_timeout");
        assertThat(never.isCancelled()).isTrue();
    }

    /**
     * 正常响应。
     */
    private RexAnalyzeResponse success() {
        return new RexAnalyzeResponse(
                "ok", "iic/nlp_deberta_rex-uninlu-chinese-base", "v1.0.2", "cpu",
                2, 0, false,
                new RexSentiment("negative", "负向情感", true, 2,
                        List.of("刑警追查十年悬案")),
                List.of(new RexTag("悬疑", 2, List.of("雨夜连环案")),
                        new RexTag("甜宠", 1, List.of("刑警追查十年悬案"))),
                false, List.of(), List.of(), 842);
    }

    /**
     * 降级响应。
     */
    private RexAnalyzeResponse degradedResponse() {
        return RexAnalyzeResponse.degraded("rex_unavailable", 12);
    }

    /**
     * 超量标签和超长证据。
     */
    private RexAnalyzeResponse oversizedResponse() {
        List<RexTag> tags = new ArrayList<>();
        for (int index = 0; index < 7; index += 1) {
            tags.add(new RexTag("标签" + index, index,
                    List.of("证".repeat(200), "据".repeat(200))));
        }
        return new RexAnalyzeResponse(
                "ok", "model", "v1.0.2", "cpu", 3, 0, false,
                new RexSentiment("negative", "负向情感", true, 3,
                        List.of("a".repeat(200), "b".repeat(200), "c".repeat(200),
                                "d".repeat(200))),
                tags, false, List.of(), List.of(), 100);
    }
}
