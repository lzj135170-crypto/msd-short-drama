package com.msd.content.service;

import com.msd.content.ai.AiExperimentService;
import com.msd.content.ai.AiMetrics;
import com.msd.content.ai.AiTokenBudgetService;
import com.msd.content.ai.RagResponseCache;
import com.msd.content.config.AiGovernanceProperties;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.RagRecommendRequest;
import com.msd.content.dto.RagRecommendResponse;
import com.msd.content.dto.RagSource;
import com.msd.content.rag.RagMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 推荐编排测试。
 */
class RagRecommendationServiceTest {

    private final RagProperties properties = new RagProperties();

    /**
     * 回答和来源应一起返回，来源包含精排分数、RRF 分数和两路排名。
     */
    @Test
    void returnsAnswerAndSources() {
        ChatClient chatClient = chatClient(response("《长安第一女仵作》——验尸破案。", List.of(document(5005))));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer()).isEqualTo("《长安第一女仵作》——验尸破案。");
        assertThat(result.sources()).hasSize(1);
        RagSource source = result.sources().get(0);
        assertThat(source.dramaId()).isEqualTo(5005L);
        assertThat(source.title()).isEqualTo("长安第一女仵作");
        assertThat(source.score()).isEqualTo(0.93);
        assertThat(source.rrfScore()).isEqualTo(0.0328);
        assertThat(source.vectorRank()).isEqualTo(1);
        assertThat(source.bm25Rank()).isEqualTo(3);
        assertThat(source.retrievalSources()).containsExactly("vector", "bm25");
        assertThat(result.degraded()).isFalse();
        assertThat(result.degradedFlags()).isEmpty();
        assertThat(result.requestId()).isNotBlank();
    }

    /**
     * 回答中的手机号应被隐藏。
     */
    @Test
    void hidesContactInfoInAnswer() {
        ChatClient chatClient = chatClient(response(
                "可以联系 13800138000 咨询。", List.of(document(5005))));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer())
                .doesNotContain("13800138000")
                .contains("[联系方式已隐藏]");
    }

    /**
     * 每次推荐生成独立 requestId，供反馈和日志关联。
     */
    @Test
    void generatesDistinctRequestIdPerCall() {
        ChatClient chatClient = chatClient(response("answer", List.of(document(5005))));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse first = service.recommend(new RagRecommendRequest("q", 3));
        RagRecommendResponse second = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(first.requestId()).isNotEqualTo(second.requestId());
    }

    /**
     * 没有检索结果时固定返回 no-match 文案，不能使用模型回答。
     */
    @Test
    void returnsFixedNoResultWhenNoDocuments() {
        ChatClient chatClient = chatClient(response("模型编造的回答", List.of()));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer()).isEqualTo(RagRecommendationService.NO_RESULT_ANSWER);
        assertThat(result.sources()).isEmpty();
        assertThat(result.degraded()).isFalse();
    }

    /**
     * 有来源但回答为空时返回生成失败兜底文案。
     */
    @Test
    void fallsBackWhenAnswerIsBlank() {
        ChatClient chatClient = chatClient(response("", List.of(document(5005))));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer()).isEqualTo(RagRecommendationService.GENERATION_FALLBACK);
        assertThat(result.sources()).hasSize(1);
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedFlags()).containsExactly("generation");
    }

    /**
     * ChatClient 抛异常时仍返回 200 语义的兜底响应。
     */
    @Test
    void catchesChatClientFailure() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                .user(anyString())
                .advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())
                .call()
                .chatClientResponse())
                .thenThrow(new RuntimeException("llm down"));

        RagRecommendationService service = new RagRecommendationService(chatClient, properties);
        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer()).isEqualTo(RagRecommendationService.NO_RESULT_ANSWER);
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedFlags()).containsExactly("generation");
    }

    /**
     * 请求 topK 控制返回来源数量。
     */
    @Test
    void truncatesSourcesByRequestedTopK() {
        ChatClient chatClient = chatClient(response("answer", List.of(
                document(1), document(2), document(3), document(4))));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 2));

        assertThat(result.sources()).hasSize(2);
    }

    /**
     * 流式推荐没有检索结果时固定下发 no-match，不采用模型回答。
     */
    @Test
    void streamReturnsNoResultWhenDocumentsEmpty() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                .user(anyString())
                .advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())
                .stream()
                .content())
                .thenReturn(Flux.just("模型编造的回答"));
        RagRecommendationService service = new RagRecommendationService(chatClient, properties);

        List<ServerSentEvent<Object>> events = service.stream(
                        new RagRecommendRequest("q", 3), null)
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("meta", "delta", "sources", "done");
        assertThat(events.get(1).data()).isEqualTo(
                new com.msd.content.dto.AiStreamDelta(RagRecommendationService.NO_RESULT_ANSWER));
    }

    /**
     * 整体超时响应固定带 timeout 标记。
     */
    @Test
    void timeoutResponseCarriesFlag() {
        RagRecommendationService service = new RagRecommendationService(
                mock(ChatClient.class, RETURNS_DEEP_STUBS), properties);

        RagRecommendResponse result = service.timeoutResponse();

        assertThat(result.answer()).isEqualTo(RagRecommendationService.NO_RESULT_ANSWER);
        assertThat(result.sources()).isEmpty();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedFlags()).containsExactly("timeout");
        assertThat(result.elapsedMs()).isEqualTo(properties.getTotalTimeoutMs());
    }

    /**
     * 功能灰度关闭时直接返回固定文案，不调用模型。
     */
    @Test
    void featureGateStopsBeforeModel() {
        ChatClient chatClient = chatClient(response("answer", List.of(document(5005))));
        AiGovernanceProperties governance = new AiGovernanceProperties();
        AiGovernanceProperties.Feature feature = new AiGovernanceProperties.Feature();
        feature.setEnabled(false);
        governance.getFeatures().put("rag-search", feature);
        RagRecommendationService service = new RagRecommendationService(
                chatClient, properties, null, new com.msd.content.ai.AiInputGuard(),
                new com.msd.content.ai.AiOutputGuard(), new AiMetrics(null),
                new AiExperimentService(governance), null, null);
        clearInvocations(chatClient);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(result.answer()).isEqualTo(RagRecommendationService.FEATURE_DISABLED_ANSWER);
        assertThat(result.degradedFlags()).containsExactly(
                RagRecommendationService.FLAG_FEATURE_DISABLED);
        verify(chatClient, never()).prompt();
    }

    /**
     * token 预算耗尽时直接返回固定文案，不调用模型。
     */
    @Test
    void budgetGateStopsBeforeModel() {
        ChatClient chatClient = chatClient(response("answer", List.of(document(5005))));
        AiTokenBudgetService budgetService = mock(AiTokenBudgetService.class);
        when(budgetService.reserve(eq("rag"), eq(1003L), anyInt()))
                .thenReturn(new AiTokenBudgetService.Reservation(
                        false, "rag", 1003L, 100, 600, 600, 500));
        RagRecommendationService service = new RagRecommendationService(
                chatClient, properties, null, new com.msd.content.ai.AiInputGuard(),
                new com.msd.content.ai.AiOutputGuard(), new AiMetrics(null),
                null, budgetService, null);
        clearInvocations(chatClient);

        RagRecommendResponse result = service.recommend(new RagRecommendRequest("q", 3), 1003L);

        assertThat(result.answer()).isEqualTo(RagRecommendationService.BUDGET_EXCEEDED_ANSWER);
        assertThat(result.degradedFlags()).containsExactly(
                RagRecommendationService.FLAG_BUDGET_EXCEEDED);
        verify(chatClient, never()).prompt();
    }

    /**
     * 相同查询命中缓存后不再调用模型，并返回新的 requestId。
     */
    @Test
    void secondIdenticalQueryHitsCache() {
        ChatClient chatClient = chatClient(response("answer", List.of(document(5005))));
        RagResponseCache cache = new RagResponseCache(new AiGovernanceProperties(), null);
        RagRecommendationService service = new RagRecommendationService(
                chatClient, properties, null, new com.msd.content.ai.AiInputGuard(),
                new com.msd.content.ai.AiOutputGuard(), new AiMetrics(null),
                null, null, cache);
        clearInvocations(chatClient);

        RagRecommendResponse first = service.recommend(new RagRecommendRequest("q", 3));
        RagRecommendResponse second = service.recommend(new RagRecommendRequest("q", 3));

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.answer()).isEqualTo("answer");
        assertThat(second.requestId()).isNotEqualTo(first.requestId());
        verify(chatClient, times(1)).prompt();
    }

    /**
     * 构造带文档上下文的 ChatClient 响应。
     */
    private ChatClientResponse response(String answer, List<Document> documents) {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage(answer))));
        Map<String, Object> context = documents.isEmpty()
                ? Map.of()
                : Map.of(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, documents);
        return new ChatClientResponse(chatResponse, context);
    }

    /**
     * 构造固定返回指定响应的 ChatClient。
     */
    private ChatClient chatClient(ChatClientResponse response) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt()
                .user(anyString())
                .advisors(ArgumentMatchers.<Consumer<ChatClient.AdvisorSpec>>any())
                .call()
                .chatClientResponse())
                .thenReturn(response);
        return chatClient;
    }

    /**
     * 构造测试文档。
     */
    private Document document(long dramaId) {
        return Document.builder()
                .id("drama-" + dramaId)
                .text("标题：长安第一女仵作")
                .metadata(Map.of(
                        RagMetadata.DRAMA_ID, dramaId,
                        RagMetadata.TITLE, "长安第一女仵作",
                        RagMetadata.SUBTITLE, "验尸破案，顺便谈个恋爱",
                        RagMetadata.COVER_URL, "http://127.0.0.1:9000/msd/covers/5005.jpg",
                        RagMetadata.SNIPPET, "长安城接连出现离奇命案。",
                        RagMetadata.RERANK_SCORE, 0.93,
                        RagMetadata.RRF_SCORE, 0.0328,
                        RagMetadata.VECTOR_RANK, 1,
                        RagMetadata.BM25_RANK, 3,
                        RagMetadata.RETRIEVAL_SOURCES, List.of("vector", "bm25")))
                .score(0.93)
                .build();
    }
}
