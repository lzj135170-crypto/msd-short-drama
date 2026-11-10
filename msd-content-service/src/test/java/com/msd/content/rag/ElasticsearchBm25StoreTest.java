package com.msd.content.rag;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.content.config.RagProperties;
import com.msd.content.index.DramaDocumentBuilder;
import com.msd.content.support.RagUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.document.Document;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Elasticsearch BM25 存储测试。
 */
class ElasticsearchBm25StoreTest {

    /**
     * 重建索引先 DELETE 再 PUT，404 表示旧索引不存在。
     */
    @Test
    void recreatesIndexWithDeleteThenPut() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> deleteResponse = response(404, "{}");
        HttpResponse<String> createResponse = response(200, "{}");
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(deleteResponse, createResponse);

        ElasticsearchBm25Store store = store(client);
        store.recreateIndex();

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(captor.capture(), any());
        assertThat(captor.getAllValues()).extracting(HttpRequest::method)
                .containsExactly("DELETE", "PUT");
        assertThat(captor.getAllValues().get(1).uri().getPath()).isEqualTo("/test-index");
    }

    /**
     * 全量写入使用 drama-{id} 作为文档 ID。
     */
    @Test
    void indexesDocumentsWithStableId() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(200, "{\"result\":\"created\"}");
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        ElasticsearchBm25Store store = store(client);
        int indexed = store.indexAll(List.of(drama()));

        assertThat(indexed).isEqualTo(1);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        assertThat(captor.getValue().method()).isEqualTo("PUT");
        assertThat(captor.getValue().uri().getPath()).isEqualTo("/test-index/_doc/drama-5005");
    }

    /**
     * 全量写入同时索引短剧和分集，分集文档 ID 为 episode-{id}。
     */
    @Test
    void indexesDramaAndEpisodeDocuments() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(200, "{\"result\":\"created\"}");
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        ElasticsearchBm25Store store = store(client);
        int indexed = store.indexAll(List.of(dramaWithEpisodes()));

        assertThat(indexed).isEqualTo(2);
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(captor.capture(), any());
        assertThat(captor.getAllValues()).extracting(request -> request.uri().getPath())
                .containsExactly("/test-index/_doc/drama-5005",
                        "/test-index/_doc/episode-6401");
    }

    /**
     * 前情检索必须同时过滤 docType、dramaId 和 episodeNo 范围。
     */
    @Test
    void searchEpisodesAddsScopeFilters() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(200, """
                {"hits":{"hits":[
                  {"_id":"episode-6401","_score":6.5,"_source":{
                    "docType":"episode",
                    "dramaId":5005,
                    "dramaTitle":"长安第一女仵作",
                    "episodeId":6401,
                    "episodeNo":1,
                    "episodeTitle":"第1集 雨夜尸案",
                    "summary":"长安雨夜发现一具无名女尸。",
                    "content":"剧名：长安第一女仵作"
                  }}
                ]}}
                """);
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        ElasticsearchBm25Store store = store(client);
        List<Document> result = store.searchEpisodes("尸案", 5005L, 5, 3);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        String body = requestBody(captor.getValue());
        assertThat(captor.getValue().uri().getPath()).isEqualTo("/test-index/_search");
        assertThat(body)
                .contains("\"docType\":\"episode\"")
                .contains("\"dramaId\":5005")
                .contains("\"episodeNo\":{\"lt\":5}");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("episode-6401");
        assertThat(result.get(0).getMetadata())
                .containsEntry(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_EPISODE)
                .containsEntry(RagMetadata.EPISODE_NO, 1)
                .containsEntry(RagMetadata.EPISODE_TITLE, "第1集 雨夜尸案")
                .containsEntry(RagMetadata.SUMMARY, "长安雨夜发现一具无名女尸。");
    }

    /**
     * 短剧检索必须带 docType=drama，避免分集文档进入推荐来源。
     */
    @Test
    void searchDramaFiltersDocType() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(200, "{\"hits\":{\"hits\":[]}}");
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        ElasticsearchBm25Store store = store(client);
        store.search("悬疑", 3);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        String body = requestBody(captor.getValue());
        assertThat(body).contains("\"docType\":\"drama\"");
    }

    /**
     * 单集更新使用同一个文档 ID 覆盖写入。
     */
    @Test
    void upsertsSingleEpisodeDocument() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(200, "{\"result\":\"updated\"}");
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        ElasticsearchBm25Store store = store(client);
        store.upsertEpisode(new DramaDocumentBuilder(new RagProperties())
                .buildEpisode(5005L, "长安第一女仵作", 6401L, 1,
                        "第1集 雨夜尸案", "长安雨夜发现一具无名女尸。"));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(captor.capture(), any());
        assertThat(captor.getValue().uri().getPath()).isEqualTo("/test-index/_doc/episode-6401");
        assertThat(requestBody(captor.getValue()))
                .contains("\"docType\":\"episode\"")
                .contains("\"summaryTokens\"");
    }

    /**
     * 检索结果还原为 Document，并带上 bm25 来源和分数。
     */
    @Test
    void parsesSearchHits() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(200, """
                {"hits":{"hits":[
                  {"_id":"drama-5005","_score":7.2,"_source":{
                    "dramaId":5005,
                    "title":"长安第一女仵作",
                    "subtitle":"验尸破案，顺便谈个恋爱",
                    "coverUrl":"http://127.0.0.1:9000/msd/covers/5005.jpg",
                    "categoryId":3,
                    "categoryName":"古装悬疑",
                    "tags":["悬疑","破案"],
                    "actors":["林一","苏晚"],
                    "episodeCount":2,
                    "viewCount":1000,
                    "likeCount":100,
                    "publishTime":"2026-01-01T10:00:00",
                    "snippet":"长安城接连出现离奇命案。",
                    "content":"标题：长安第一女仵作"
                  }}
                ]}}
                """);
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        ElasticsearchBm25Store store = store(client);
        List<Document> result = store.search("悬疑 破案", 20);

        assertThat(result).hasSize(1);
        Document document = result.get(0);
        assertThat(document.getId()).isEqualTo("drama-5005");
        assertThat(document.getText()).isEqualTo("标题：长安第一女仵作");
        assertThat(document.getMetadata())
                .containsEntry(RagMetadata.DRAMA_ID, 5005L)
                .containsEntry(RagMetadata.TITLE, "长安第一女仵作")
                .containsEntry(RagMetadata.TAGS, List.of("悬疑", "破案"))
                .containsEntry(RagMetadata.RETRIEVAL_SOURCE, "bm25")
                .containsEntry(RagMetadata.BM25_SCORE, 7.2);
    }

    /**
     * 非 2xx 响应抛出不可用异常。
     */
    @Test
    void throwsWhenSearchFails() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> httpResponse = response(500, "{\"error\":\"boom\"}");
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        ElasticsearchBm25Store store = store(client);

        assertThatThrownBy(() -> store.search("悬疑", 20))
                .isInstanceOf(RagUnavailableException.class)
                .hasMessageContaining("ES 检索失败");
    }

    /**
     * ping 根据索引是否存在返回可用性。
     */
    @Test
    void pingsIndex() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> found = response(200, "{}");
        HttpResponse<String> missing = response(404, "{}");
        when(client.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(found, missing);

        ElasticsearchBm25Store store = store(client);

        assertThat(store.ping()).isTrue();
        assertThat(store.ping()).isFalse();
    }

    /**
     * 构造被测存储。
     */
    private ElasticsearchBm25Store store(HttpClient client) {
        RagProperties properties = new RagProperties();
        properties.getElasticsearch().setIndexName("test-index");
        properties.getElasticsearch().setReadTimeoutMs(1_000);
        properties.getBm25().setFailureBackoffMs(1_000);
        JsonMapper jsonMapper = JsonMapper.shared();
        ChineseTokenizer tokenizer = new ChineseTokenizer();
        DramaDocumentBuilder builder = new DramaDocumentBuilder(properties);
        return new ElasticsearchBm25Store(properties, jsonMapper, tokenizer, builder, client);
    }

    /**
     * 构造测试短剧。
     */
    private DramaRagExportDTO drama() {
        return new DramaRagExportDTO(
                5005L,
                "长安第一女仵作",
                "验尸破案，顺便谈个恋爱",
                "http://127.0.0.1:9000/msd/covers/5005.jpg",
                "长安城接连出现离奇命案。",
                3L,
                "古装悬疑",
                List.of("悬疑", "破案"),
                List.of("林一", "苏晚"),
                2,
                List.of("第1集 验尸", "第2集 追凶"),
                "2026-01-01T10:00:00",
                1000L,
                100L,
                50L,
                20L);
    }

    /**
     * 读取请求体，便于断言 ES DSL。
     */
    private String requestBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * 构造带一集明细的短剧。
     */
    private DramaRagExportDTO dramaWithEpisodes() {
        DramaRagExportDTO drama = drama();
        return new DramaRagExportDTO(
                drama.id(), drama.title(), drama.subtitle(), drama.coverUrl(),
                drama.description(), drama.categoryId(), drama.categoryName(),
                drama.tags(), drama.actors(), drama.episodeCount(), drama.episodeTitles(),
                drama.publishTime(), drama.viewCount(), drama.likeCount(),
                drama.favoriteCount(), drama.commentCount(),
                List.of(new EpisodeRagExportDTO(
                        6401L, 1, "第1集 雨夜尸案", "长安雨夜发现一具无名女尸。")));
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
