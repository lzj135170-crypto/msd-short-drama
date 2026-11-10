package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DashScope Rerank 精排与降级测试。
 */
class DashScopeRerankPostProcessorTest {

    /**
     * 正常响应按 relevance_score 降序取 topN，并写入 rerankScore。
     */
    @Test
    void reranksByRelevanceScore() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(200, """
                {"output":{"results":[
                  {"index":3,"relevance_score":0.93},
                  {"index":1,"relevance_score":0.88},
                  {"index":4,"relevance_score":0.71}
                ]}}
                """);
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        RagProperties properties = properties("test-key");
        DashScopeRerankPostProcessor processor = processor(properties, client);
        RagTrace trace = new RagTrace("original query");
        trace.setRequestedTopK(3);

        List<Document> result = processor.process(query(trace), documents(5));

        assertThat(result).extracting(Document::getId)
                .containsExactly("drama-3", "drama-1", "drama-4");
        assertThat(result.get(0).getMetadata())
                .containsEntry(RagMetadata.RERANK_SCORE, 0.93);
        assertThat(trace.getFinalDocuments()).hasSize(3);
        assertThat(trace.getDegradedFlags()).isEmpty();
    }

    /**
     * 低于 minScore 的候选不进入最终来源，全部低于阈值时返回空。
     */
    @Test
    void filtersCandidatesBelowMinScore() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(200, """
                {"output":{"results":[
                  {"index":0,"relevance_score":0.9},
                  {"index":1,"relevance_score":0.2},
                  {"index":2,"relevance_score":0.1}
                ]}}
                """);
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);
        RagProperties properties = properties("test-key");
        properties.getRerank().setMinScore(0.25);
        DashScopeRerankPostProcessor processor = processor(properties, client);
        RagTrace trace = new RagTrace("original query");
        trace.setRequestedTopK(3);

        List<Document> result = processor.process(query(trace), documents(5));

        assertThat(result).extracting(Document::getId).containsExactly("drama-0");
        assertThat(trace.getFinalDocuments()).hasSize(1);
        assertThat(trace.getDegradedFlags()).isEmpty();
    }

    /**
     * 请求异常时回退 RRF 前 topN，并标记 rerank 降级。
     */
    @Test
    void fallsBackWhenRequestFails() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("boom"));

        RagProperties properties = properties("test-key");
        DashScopeRerankPostProcessor processor = processor(properties, client);
        RagTrace trace = new RagTrace("original query");
        trace.setRequestedTopK(3);

        List<Document> result = processor.process(query(trace), documents(5));

        assertThat(result).extracting(Document::getId)
                .containsExactly("drama-0", "drama-1", "drama-2");
        assertThat(trace.getDegradedFlags()).containsExactly("rerank");
        assertThat(trace.getFinalDocuments()).hasSize(3);
    }

    /**
     * 非 2xx 响应也回退，不把错误响应当结果。
     */
    @Test
    void fallsBackWhenStatusIsNotSuccess() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(500, "{\"message\":\"error\"}");
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        RagProperties properties = properties("test-key");
        DashScopeRerankPostProcessor processor = processor(properties, client);
        RagTrace trace = new RagTrace("original query");
        trace.setRequestedTopK(3);

        List<Document> result = processor.process(query(trace), documents(5));

        assertThat(result).extracting(Document::getId)
                .containsExactly("drama-0", "drama-1", "drama-2");
        assertThat(trace.getDegradedFlags()).containsExactly("rerank");
    }

    /**
     * 未配置 API Key 时不发起 HTTP 请求。
     */
    @Test
    void skipsHttpWhenApiKeyMissing() {
        HttpClient client = mock(HttpClient.class);
        RagProperties properties = properties("");
        DashScopeRerankPostProcessor processor = processor(properties, client);
        RagTrace trace = new RagTrace("original query");
        trace.setRequestedTopK(3);

        List<Document> result = processor.process(query(trace), documents(5));

        assertThat(result).extracting(Document::getId)
                .containsExactly("drama-0", "drama-1", "drama-2");
        assertThat(trace.getDegradedFlags()).containsExactly("rerank");
        verifyNoInteractions(client);
    }

    /**
     * 候选数不超过 topN 时直接返回，不调用 Rerank。
     */
    @Test
    void returnsDocumentsWhenAlreadyWithinTopN() {
        HttpClient client = mock(HttpClient.class);
        RagProperties properties = properties("test-key");
        DashScopeRerankPostProcessor processor = processor(properties, client);
        RagTrace trace = new RagTrace("original query");
        trace.setRequestedTopK(3);

        List<Document> result = processor.process(query(trace), documents(2));

        assertThat(result).hasSize(2);
        assertThat(trace.getFinalDocuments()).hasSize(2);
        assertThat(trace.getDegradedFlags()).isEmpty();
        verifyNoInteractions(client);
    }

    /**
     * 构造测试配置。
     */
    private RagProperties properties(String apiKey) {
        RagProperties properties = new RagProperties();
        properties.getRerank().setApiKey(apiKey);
        properties.getRerank().setTopN(3);
        properties.getRerank().setTimeoutMs(500);
        properties.getRerank().setFailureBackoffMs(30_000);
        return properties;
    }

    /**
     * 构造被测精排器。
     */
    private DashScopeRerankPostProcessor processor(RagProperties properties, HttpClient client) {
        return new DashScopeRerankPostProcessor(properties, JsonMapper.shared(), client);
    }

    /**
     * 构造带 trace 的查询。
     */
    private Query query(RagTrace trace) {
        return Query.builder()
                .text("rewritten keywords")
                .context(Map.of(RagTrace.CONTEXT_KEY, trace))
                .build();
    }

    /**
     * 构造 5 条候选文档。
     */
    private List<Document> documents(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> Document.builder()
                        .id("drama-" + index)
                        .text("doc-" + index)
                        .metadata(Map.of(RagMetadata.TITLE, "D" + index))
                        .score(1.0 / (index + 1))
                        .build())
                .toList();
    }

    /**
     * 构造 HTTP 响应。
     */
    private HttpResponse<String> response(int status, String body) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
