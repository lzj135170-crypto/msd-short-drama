package com.msd.content.rag;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.content.config.RagProperties;
import com.msd.content.index.DramaDocumentBuilder;
import com.msd.content.support.JsonHttpSupport;
import com.msd.content.support.JsonHttpSupport.HttpResult;
import com.msd.content.support.RagUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 8 BM25 索引与检索。
 *
 * <p>中文先由 jieba 预分词，ES 只使用 whitespace analyzer；索引和查询共用
 * {@link ChineseTokenizer}，避免 standard analyzer 把中文拆成单字。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ElasticsearchBm25Store implements Bm25IndexStore {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchBm25Store.class);
    /** 错误信息最大字符数。 */
    private static final int MAX_ERROR_CHARS = 500;
    /** 写入/重建索引的最小超时（毫秒）。 */
    private static final long MIN_WRITE_TIMEOUT_MS = 10_000;

    // Elasticsearch 与 BM25 配置
    private final RagProperties properties;
    // JSON 序列化器
    private final JsonMapper jsonMapper;
    // 中文预分词器
    private final ChineseTokenizer tokenizer;
    // 文档构建器，保证 ES 与 Milvus 字段一致
    private final DramaDocumentBuilder documentBuilder;
    // 复用的 HTTP 客户端
    private final HttpClient httpClient;

    // 失败退避截止时间
    private volatile long unavailableUntil;
    // 最近一次失败原因
    private volatile String lastError;

    /**
     * Spring 使用的构造器，按连接超时创建独立 HttpClient。
     */
    @Autowired
    public ElasticsearchBm25Store(RagProperties properties,
                                  JsonMapper jsonMapper,
                                  ChineseTokenizer tokenizer,
                                  DramaDocumentBuilder documentBuilder) {
        this(properties, jsonMapper, tokenizer, documentBuilder, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getElasticsearch().getConnectTimeoutMs()))
                .build());
    }

    /**
     * 测试使用的构造器，允许注入 HttpClient。
     */
    ElasticsearchBm25Store(RagProperties properties,
                           JsonMapper jsonMapper,
                           ChineseTokenizer tokenizer,
                           DramaDocumentBuilder documentBuilder,
                           HttpClient httpClient) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.tokenizer = tokenizer;
        this.documentBuilder = documentBuilder;
        this.httpClient = httpClient;
    }

    /**
     * 删除并重建索引结构。
     */
    @Override
    public void recreateIndex() {
        // 1. 删除旧索引；索引本来不存在时 404 视为正常
        HttpResult delete = JsonHttpSupport.send(
                httpClient, "DELETE", indexUrl(), null, writeTimeoutMs());
        if (!delete.successful() && delete.status() != 404) {
            failAndThrow("删除 ES 索引失败", delete);
        }

        // 2. 重建 settings 和 mapping
        HttpResult create = JsonHttpSupport.send(
                httpClient, "PUT", indexUrl(), mappingJson(), writeTimeoutMs());
        if (!create.successful()) {
            failAndThrow("创建 ES 索引失败", create);
        }
        markSuccess();
        log.info("elasticsearch index recreated: {}", properties.getElasticsearch().getIndexName());
    }

    /**
     * 逐条写入短剧文档和分集文档，返回成功条数。
     */
    @Override
    public int indexAll(List<DramaRagExportDTO> dramas) {
        int indexed = 0;
        for (DramaRagExportDTO drama : dramas) {
            if (drama == null || drama.id() == null) {
                continue;
            }
            // 1. 文档 ID 和 Milvus 保持一致：drama-{id}
            indexed += putDocument("drama-" + drama.id(), sourceJson(drama));
            // 2. 分集文档使用 episode-{id}，供 AI 伴看前情检索
            if (drama.episodes() == null) {
                continue;
            }
            for (EpisodeRagExportDTO episode : drama.episodes()) {
                if (episode == null || episode.id() == null) {
                    continue;
                }
                indexed += putDocument("episode-" + episode.id(),
                        episodeSourceJson(drama, episode));
            }
        }
        markSuccess();
        return indexed;
    }

    /**
     * 单集摘要回写后更新一条分集文档。
     */
    @Override
    public void upsertEpisode(Document document) {
        // 1. BM25 关闭或文档无效时直接跳过
        if (!properties.getBm25().isEnabled() || document == null || document.getId() == null) {
            return;
        }
        // 2. 使用同一文档 ID 覆盖写入，失败进入退避
        HttpResult result = JsonHttpSupport.send(
                httpClient, "PUT", indexUrl() + "/_doc/" + document.getId(),
                episodeSourceJson(document), writeTimeoutMs());
        if (!result.successful()) {
            failAndThrow("写入分集 " + document.getId() + " 失败", result);
        }
        markSuccess();
    }

    /**
     * 按 dramaId 删除短剧文档和分集文档。
     *
     * <p>使用 _delete_by_query 并强制刷新，保证下架后立即不可检索。</p>
     */
    @Override
    public int deleteByDrama(Long dramaId) {
        // 1. 关闭或参数非法时无需处理
        if (!properties.getBm25().isEnabled() || dramaId == null || dramaId <= 0) {
            return 0;
        }
        // 2. 按 dramaId 过滤删除；索引不存在时视为已删除
        ObjectNode root = jsonMapper.createObjectNode();
        root.putObject("query").putObject("term").put(RagMetadata.DRAMA_ID, dramaId);
        HttpResult result = JsonHttpSupport.send(
                httpClient, "POST",
                indexUrl() + "/_delete_by_query?conflicts=proceed&refresh=true",
                jsonMapper.writeValueAsString(root), writeTimeoutMs());
        if (result.status() == 404) {
            return 0;
        }
        if (!result.successful()) {
            failAndThrow("删除短剧 " + dramaId + " 的 ES 文档失败", result);
        }
        markSuccess();
        return jsonMapper.readTree(result.body()).path("deleted").asInt(0);
    }

    /**
     * 删除某集的全部字幕片段文档。
     */
    @Override
    public int deleteTranscriptsByEpisode(Long episodeId) {
        if (!properties.getBm25().isEnabled() || episodeId == null || episodeId <= 0) {
            return 0;
        }
        ObjectNode root = jsonMapper.createObjectNode();
        ArrayNode filter = root.putObject("query").putObject("bool").putArray("filter");
        filter.add(termFilter(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_TRANSCRIPT));
        filter.add(termFilter(RagMetadata.EPISODE_ID, episodeId));
        HttpResult result = JsonHttpSupport.send(
                httpClient, "POST",
                indexUrl() + "/_delete_by_query?conflicts=proceed&refresh=true",
                jsonMapper.writeValueAsString(root), writeTimeoutMs());
        if (result.status() == 404) {
            return 0;
        }
        if (!result.successful()) {
            failAndThrow("删除剧集 " + episodeId + " 的字幕文档失败", result);
        }
        markSuccess();
        return jsonMapper.readTree(result.body()).path("deleted").asInt(0);
    }

    /**
     * 写入单集字幕片段，调用方随后刷新索引。
     */
    @Override
    public int upsertTranscripts(List<Document> documents) {
        if (!properties.getBm25().isEnabled() || documents == null || documents.isEmpty()) {
            return 0;
        }
        int indexed = 0;
        for (Document document : documents) {
            if (document == null || document.getId() == null) {
                continue;
            }
            indexed += putDocument(document.getId(), transcriptSourceJson(document));
        }
        markSuccess();
        return indexed;
    }

    /**
     * 检索字幕片段，只返回指定短剧当前集及之前的内容。
     */
    @Override
    public List<Document> searchTranscripts(String queryText, Long dramaId,
                                            Integer maxEpisodeNo, int topK) {
        if (dramaId == null || maxEpisodeNo == null || maxEpisodeNo <= 0) {
            return List.of();
        }
        return search(queryText, Math.max(topK, 1), List.of(
                termFilter(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_TRANSCRIPT),
                termFilter(RagMetadata.DRAMA_ID, dramaId),
                rangeAtMost(RagMetadata.EPISODE_NO, maxEpisodeNo)));
    }

    /**
     * 刷新索引，保证重建后立即可查。
     */
    @Override
    public void refresh() {
        HttpResult result = JsonHttpSupport.send(
                httpClient, "POST", indexUrl() + "/_refresh", null, writeTimeoutMs());
        if (!result.successful()) {
            failAndThrow("刷新 ES 索引失败", result);
        }
        markSuccess();
    }

    /**
     * 检查索引是否存在且可访问。
     */
    @Override
    public boolean ping() {
        // 1. 退避期内不探测
        if (System.currentTimeMillis() < unavailableUntil) {
            return false;
        }
        try {
            HttpResult result = JsonHttpSupport.send(
                    httpClient, "GET", indexUrl(), null, readTimeoutMs());
            if (result.status() == 404) {
                lastError = "索引不存在";
                return false;
            }
            if (!result.successful()) {
                markFailure("ES ping 失败：HTTP " + result.status());
                return false;
            }
            markSuccess();
            return true;
        } catch (RagUnavailableException e) {
            markFailure(e.getMessage());
            return false;
        }
    }

    /**
     * 执行短剧 BM25 检索，返回按 ES 分数排序的文档。
     */
    public List<Document> search(String queryText, int topK) {
        return searchDrama(queryText, topK);
    }

    /**
     * 只检索短剧文档，避免分集摘要进入推荐来源。
     */
    public List<Document> searchDrama(String queryText, int topK) {
        return search(queryText, Math.max(topK, 1),
                List.of(termFilter(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_DRAMA)));
    }

    /**
     * 检索当前集之前的分集文档，剧集和集数由调用方限定。
     */
    public List<Document> searchEpisodes(String queryText, Long dramaId,
                                         Integer beforeEpisodeNo, int topK) {
        // 1. 第 1 集或缺少过滤条件时没有前情可检索
        if (dramaId == null || beforeEpisodeNo == null || beforeEpisodeNo <= 1) {
            return List.of();
        }
        // 2. docType、短剧 ID 和集数范围同时约束，防止跨剧或剧透
        return search(queryText, Math.max(topK, 1), List.of(
                termFilter(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_EPISODE),
                termFilter(RagMetadata.DRAMA_ID, dramaId),
                rangeBefore(RagMetadata.EPISODE_NO, beforeEpisodeNo)));
    }

    /**
     * 统计某部短剧已索引的分集数量。
     *
     * @return 分集数量；ES 关闭或不可用时返回 -1，调用方不做索引缺失判断
     */
    public long countEpisodes(Long dramaId) {
        if (!properties.getBm25().isEnabled() || dramaId == null) {
            return -1;
        }
        try {
            // 1. 只统计 docType=episode 且 dramaId 匹配的文档
            ObjectNode root = jsonMapper.createObjectNode();
            ArrayNode filter = root.putObject("query").putObject("bool").putArray("filter");
            filter.add(termFilter(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_EPISODE));
            filter.add(termFilter(RagMetadata.DRAMA_ID, dramaId));
            HttpResult result = JsonHttpSupport.send(
                    httpClient, "POST", indexUrl() + "/_count",
                    jsonMapper.writeValueAsString(root), readTimeoutMs());
            // 2. 非 2xx 视为未知，不影响主检索
            if (!result.successful()) {
                return -1;
            }
            return jsonMapper.readTree(result.body()).path("count").asLong(-1);
        } catch (Exception e) {
            log.debug("elasticsearch episode count failed: dramaId={}, error={}",
                    dramaId, rootMessage(e));
            return -1;
        }
    }

    /**
     * 执行 BM25 检索，返回按 ES 分数排序的文档。
     */
    private List<Document> search(String queryText, int topK, List<ObjectNode> filters) {
        if (!properties.getBm25().isEnabled()) {
            return List.of();
        }
        if (System.currentTimeMillis() < unavailableUntil) {
            throw new RagUnavailableException("Elasticsearch 暂不可用：" + lastError);
        }

        // 1. 查询侧分词；全是停用词时直接返回空
        String tokens = String.join(" ", tokenizer.tokenizeForQuery(queryText));
        if (tokens.isBlank()) {
            return List.of();
        }

        // 2. 构造多字段 should 查询，检索失败进入退避
        try {
            HttpResult result = JsonHttpSupport.send(
                    httpClient, "POST", indexUrl() + "/_search",
                    searchDsl(tokens, topK, filters), readTimeoutMs());
            if (result.status() == 404) {
                markFailure("ES 索引不存在");
                throw new RagUnavailableException("ES 索引不存在：" + properties.getElasticsearch().getIndexName());
            }
            if (!result.successful()) {
                String message = "ES 检索失败：HTTP " + result.status() + " " + truncate(result.body());
                markFailure(message);
                throw new RagUnavailableException(message);
            }

            List<Document> documents = parseHits(result.body());
            markSuccess();
            return documents;
        } catch (RagUnavailableException e) {
            if (e.getMessage() != null && !e.getMessage().startsWith("ES ")) {
                markFailure(e.getMessage());
            }
            throw e;
        } catch (Exception e) {
            String message = "ES 检索解析失败：" + rootMessage(e);
            markFailure(message);
            throw new RagUnavailableException(message, e);
        }
    }

    /**
     * 最近一次失败原因。
     */
    @Override
    public String getLastError() {
        return lastError;
    }

    /**
     * 构造索引 settings 和 mapping。
     */
    private String mappingJson() {
        ObjectNode root = jsonMapper.createObjectNode();

        // 1. 单分片零副本，本地开发不浪费资源
        ObjectNode settings = root.putObject("settings");
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);

        // 2. jieba 预分词后按空格切分，只做 lowercase
        ObjectNode analyzer = settings.putObject("analysis").putObject("analyzer");
        ObjectNode jieba = analyzer.putObject("jieba_ws");
        jieba.put("type", "custom");
        jieba.put("tokenizer", "whitespace");
        jieba.putArray("filter").add("lowercase");

        // 3. dynamic=false，未声明字段不参与索引
        ObjectNode mappings = root.putObject("mappings");
        mappings.put("dynamic", false);
        ObjectNode properties = mappings.putObject("properties");

        addField(properties, "docType", "keyword", null);
        addField(properties, "dramaId", "long", null);
        addField(properties, "dramaTitle", "keyword", null);
        addField(properties, "title", "keyword", null);
        addField(properties, "subtitle", "keyword", null);
        addField(properties, "episodeId", "long", null);
        addField(properties, "episodeNo", "integer", null);
        addField(properties, "episodeTitle", "keyword", null);
        addField(properties, "pricePoints", "integer", null);
        addField(properties, "startMs", "long", null);
        addField(properties, "endMs", "long", null);
        addTextField(properties, "transcriptTokens");
        addTextField(properties, "titleTokens");
        addTextField(properties, "subtitleTokens");
        addTextField(properties, "descriptionTokens");
        addTextField(properties, "tagTokens");
        addTextField(properties, "actorTokens");
        addTextField(properties, "episodeTokens");
        addTextField(properties, "episodeTitleTokens");
        addTextField(properties, "summaryTokens");
        addTextField(properties, "searchText");
        addField(properties, "tags", "keyword", null);
        addField(properties, "actors", "keyword", null);
        addField(properties, "episodeTitles", "keyword", null);
        addField(properties, "coverUrl", "keyword", null);
        addField(properties, "categoryId", "long", null);
        addField(properties, "categoryName", "keyword", null);
        addField(properties, "episodeCount", "integer", null);
        addField(properties, "viewCount", "long", null);
        addField(properties, "likeCount", "long", null);
        addField(properties, "publishTime", "date", null);
        addNotIndexedField(properties, "snippet");
        addNotIndexedField(properties, "summary");
        addNotIndexedField(properties, "content");

        return jsonMapper.writeValueAsString(root);
    }

    /**
     * 声明普通字段。
     */
    private void addField(ObjectNode properties, String name, String type, String analyzer) {
        ObjectNode field = properties.putObject(name);
        field.put("type", type);
        if (analyzer != null) {
            field.put("analyzer", analyzer);
        }
    }

    /**
     * 声明 jieba 分词文本字段。
     */
    private void addTextField(ObjectNode properties, String name) {
        addField(properties, name, "text", "jieba_ws");
    }

    /**
     * 声明只存储、不索引的字段。
     */
    private void addNotIndexedField(ObjectNode properties, String name) {
        ObjectNode field = properties.putObject(name);
        field.put("type", "text");
        field.put("index", false);
    }

    /**
     * 构造单条短剧的 ES source。
     */
    private String sourceJson(DramaRagExportDTO drama) {
        // 1. 复用 DocumentBuilder 的 metadata 和正文，保证两个索引字段一致
        Document document = documentBuilder.build(drama);
        ObjectNode source = jsonMapper.createObjectNode();
        for (Map.Entry<String, Object> entry : document.getMetadata().entrySet()) {
            JsonNode value = jsonMapper.valueToTree(entry.getValue());
            source.set(entry.getKey(), value);
        }

        // 2. 空日期不写入 date 字段
        if (drama.publishTime() == null || drama.publishTime().isBlank()) {
            source.remove(RagMetadata.PUBLISH_TIME);
        }

        // 3. 正文和预分词字段
        source.put("content", document.getText());
        source.put("titleTokens", tokenizer.joinForIndex(drama.title()));
        source.put("subtitleTokens", tokenizer.joinForIndex(drama.subtitle()));
        source.put("descriptionTokens", tokenizer.joinForIndex(drama.description()));
        source.put("tagTokens", tokenizer.joinForIndex(join(drama.tags())));
        source.put("actorTokens", tokenizer.joinForIndex(join(drama.actors())));
        source.put("episodeTokens", tokenizer.joinForIndex(join(drama.episodeTitles())));
        source.put("searchText", tokenizer.joinForIndex(document.getText()));

        // 4. 剧集标题保留原始值，供来源展示
        ArrayNode episodeTitles = source.putArray("episodeTitles");
        if (drama.episodeTitles() != null) {
            drama.episodeTitles().forEach(episodeTitles::add);
        }

        return jsonMapper.writeValueAsString(source);
    }

    /**
     * 构造单集文档的 ES source。
     */
    private String episodeSourceJson(DramaRagExportDTO drama, EpisodeRagExportDTO episode) {
        return episodeSourceJson(documentBuilder.buildEpisode(drama, episode));
    }

    /**
     * 把分集 Document 转换为 ES source，正文和预分词字段一次生成。
     */
    private String episodeSourceJson(Document document) {
        ObjectNode source = jsonMapper.createObjectNode();
        Map<String, Object> metadata = document.getMetadata();
        // 1. 复制 metadata，保留 docType、dramaId、episodeNo 和摘要
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            source.set(entry.getKey(), jsonMapper.valueToTree(entry.getValue()));
        }

        // 2. 正文、分集标题和摘要使用 jieba 预分词
        source.put("content", document.getText());
        source.put("episodeTitleTokens",
                tokenizer.joinForIndex(stringValue(metadata.get(RagMetadata.EPISODE_TITLE))));
        source.put("summaryTokens",
                tokenizer.joinForIndex(stringValue(metadata.get(RagMetadata.SUMMARY))));
        source.put("searchText", tokenizer.joinForIndex(document.getText()));
        return jsonMapper.writeValueAsString(source);
    }

    /**
     * 把字幕 Document 转换为 ES source，正文和预分词字段一次生成。
     */
    private String transcriptSourceJson(Document document) {
        ObjectNode source = jsonMapper.createObjectNode();
        Map<String, Object> metadata = document.getMetadata();
        // 1. 复制 metadata，保留 docType、dramaId、episodeNo、startMs/endMs 和字幕正文
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            source.set(entry.getKey(), jsonMapper.valueToTree(entry.getValue()));
        }
        // 2. 正文使用 jieba 预分词，时间轴参与检索文本
        String transcript = stringValue(metadata.get(RagMetadata.TRANSCRIPT));
        source.put("content", document.getText());
        source.put("transcriptTokens", tokenizer.joinForIndex(transcript));
        source.put("searchText", tokenizer.joinForIndex(document.getText()));
        return jsonMapper.writeValueAsString(source);
    }

    /**
     * 构造多字段 BM25 查询，支持附加过滤条件。
     */
    private String searchDsl(String tokens, int topK, List<ObjectNode> filters) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("size", topK);
        root.put("track_total_hits", false);

        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode should = bool.putArray("should");
        addMatch(should, "titleTokens", tokens, 4.0);
        addMatch(should, "tagTokens", tokens, 3.0);
        addMatch(should, "subtitleTokens", tokens, 2.5);
        addMatch(should, "actorTokens", tokens, 2.0);
        addMatch(should, "descriptionTokens", tokens, 1.2);
        addMatch(should, "episodeTokens", tokens, 1.0);
        addMatch(should, "episodeTitleTokens", tokens, 3.0);
        addMatch(should, "summaryTokens", tokens, 2.0);
        addMatch(should, "transcriptTokens", tokens, 3.0);
        addMatch(should, "searchText", tokens, 0.5);
        bool.put("minimum_should_match", 1);

        // 过滤条件单独放在 filter，不参与打分
        if (filters != null && !filters.isEmpty()) {
            ArrayNode filterArray = bool.putArray("filter");
            filters.forEach(filterArray::add);
        }

        return jsonMapper.writeValueAsString(root);
    }

    /**
     * 构造 term 过滤条件。
     */
    private ObjectNode termFilter(String field, Object value) {
        ObjectNode filter = jsonMapper.createObjectNode();
        filter.putObject("term").set(field, jsonMapper.valueToTree(value));
        return filter;
    }

    /**
     * 构造 episodeNo 小于指定值的范围过滤条件。
     */
    private ObjectNode rangeBefore(String field, int before) {
        ObjectNode filter = jsonMapper.createObjectNode();
        filter.putObject("range").putObject(field).put("lt", before);
        return filter;
    }

    /**
     * 构造小于等于指定值的范围过滤条件。
     */
    private ObjectNode rangeAtMost(String field, int value) {
        ObjectNode filter = jsonMapper.createObjectNode();
        filter.putObject("range").putObject(field).put("lte", value);
        return filter;
    }

    /**
     * 写入单条文档，成功返回 1。
     */
    private int putDocument(String documentId, String source) {
        HttpResult result = JsonHttpSupport.send(
                httpClient, "PUT", indexUrl() + "/_doc/" + documentId, source, writeTimeoutMs());
        if (!result.successful()) {
            failAndThrow("写入文档 " + documentId + " 失败", result);
        }
        return 1;
    }

    /**
     * 追加一个 match 子句。
     */
    private void addMatch(ArrayNode should, String field, String tokens, double boost) {
        ObjectNode fieldQuery = should.addObject()
                .putObject("match")
                .putObject(field);
        fieldQuery.put("query", tokens);
        fieldQuery.put("boost", boost);
    }

    /**
     * 解析 ES hits 为 Spring AI Document。
     */
    private List<Document> parseHits(String body) {
        JsonNode root = jsonMapper.readTree(body);
        JsonNode hits = root.path("hits").path("hits");
        // 1. 遍历命中结果，跳过缺失 _source 的文档
        List<Document> documents = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            if (source.isMissingNode()) {
                continue;
            }
            String docType = source.path(RagMetadata.DOC_TYPE)
                    .asString(RagMetadata.DOC_TYPE_DRAMA);
            String id = hit.path("_id").asString("");
            if (id.isBlank()) {
                id = RagMetadata.DOC_TYPE_EPISODE.equals(docType)
                        ? "episode-" + source.path(RagMetadata.EPISODE_ID).asLong()
                        : "drama-" + source.path(RagMetadata.DRAMA_ID).asLong();
            }
            double score = hit.path("_score").asDouble(0.0);

            // 2. 还原业务 metadata 并标记 BM25 来源
            Map<String, Object> metadata = metadata(source);
            metadata.put(RagMetadata.RETRIEVAL_SOURCE, "bm25");
            metadata.put(RagMetadata.BM25_SCORE, score);

            documents.add(Document.builder()
                    .id(id)
                    .text(source.path("content").asString(""))
                    .metadata(metadata)
                    .score(score)
                    .build());
        }
        return List.copyOf(documents);
    }

    /**
     * 从 _source 还原业务 metadata。
     */
    private Map<String, Object> metadata(JsonNode source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagMetadata.DOC_TYPE, source.path(RagMetadata.DOC_TYPE)
                .asString(RagMetadata.DOC_TYPE_DRAMA));
        metadata.put(RagMetadata.DRAMA_ID, source.path(RagMetadata.DRAMA_ID).asLong());
        metadata.put(RagMetadata.DRAMA_TITLE, source.path(RagMetadata.DRAMA_TITLE).asString(""));
        metadata.put(RagMetadata.EPISODE_ID, source.path(RagMetadata.EPISODE_ID).asLong());
        metadata.put(RagMetadata.EPISODE_NO, source.path(RagMetadata.EPISODE_NO).asInt());
        metadata.put(RagMetadata.EPISODE_TITLE, source.path(RagMetadata.EPISODE_TITLE).asString(""));
        metadata.put(RagMetadata.SUMMARY, source.path(RagMetadata.SUMMARY).asString(""));
        metadata.put(RagMetadata.PRICE_POINTS, source.path(RagMetadata.PRICE_POINTS).asInt(0));
        metadata.put(RagMetadata.START_MS, source.path(RagMetadata.START_MS).asInt(0));
        metadata.put(RagMetadata.END_MS, source.path(RagMetadata.END_MS).asInt(0));
        metadata.put(RagMetadata.TRANSCRIPT, source.path(RagMetadata.TRANSCRIPT).asString(""));
        metadata.put(RagMetadata.TITLE, source.path(RagMetadata.TITLE).asString(""));
        metadata.put(RagMetadata.SUBTITLE, source.path(RagMetadata.SUBTITLE).asString(""));
        metadata.put(RagMetadata.COVER_URL, source.path(RagMetadata.COVER_URL).asString(""));
        metadata.put(RagMetadata.CATEGORY_ID, source.path(RagMetadata.CATEGORY_ID).asLong());
        metadata.put(RagMetadata.CATEGORY_NAME, source.path(RagMetadata.CATEGORY_NAME).asString(""));
        metadata.put(RagMetadata.TAGS, stringList(source.path(RagMetadata.TAGS)));
        metadata.put(RagMetadata.ACTORS, stringList(source.path(RagMetadata.ACTORS)));
        metadata.put(RagMetadata.EPISODE_COUNT, source.path(RagMetadata.EPISODE_COUNT).asInt());
        metadata.put(RagMetadata.VIEW_COUNT, source.path(RagMetadata.VIEW_COUNT).asLong());
        metadata.put(RagMetadata.LIKE_COUNT, source.path(RagMetadata.LIKE_COUNT).asLong());
        metadata.put(RagMetadata.PUBLISH_TIME, source.path(RagMetadata.PUBLISH_TIME).asString(""));
        metadata.put(RagMetadata.SNIPPET, source.path(RagMetadata.SNIPPET).asString(""));
        return metadata;
    }

    /**
     * 读取 JSON 字符串数组。
     */
    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asString("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    /**
     * 拼接列表，空列表返回空字符串。
     */
    private String join(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(" ", values);
    }

    /**
     * 任意对象转字符串，null 返回空字符串。
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 标记 ES 不可用并进入退避。
     */
    private void markFailure(String message) {
        this.lastError = truncate(message);
        this.unavailableUntil = System.currentTimeMillis()
                + properties.getBm25().getFailureBackoffMs();
        log.warn("elasticsearch marked unavailable: {}", this.lastError);
    }

    /**
     * 清除失败状态。
     */
    private void markSuccess() {
        this.lastError = null;
        this.unavailableUntil = 0;
    }

    /**
     * 非 2xx 时标记失败并抛出。
     */
    private void failAndThrow(String action, HttpResult result) {
        String message = action + "：HTTP " + result.status() + " " + truncate(result.body());
        markFailure(message);
        throw new RagUnavailableException(message);
    }

    /**
     * 索引文件名，baseUrl 去掉末尾斜杠。
     */
    private String indexUrl() {
        String base = properties.getElasticsearch().getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + properties.getElasticsearch().getIndexName();
    }

    /**
     * 查询超时。
     */
    private long readTimeoutMs() {
        return properties.getElasticsearch().getReadTimeoutMs();
    }

    /**
     * 写入/重建超时，至少 10 秒。
     */
    private long writeTimeoutMs() {
        return Math.max(readTimeoutMs(), MIN_WRITE_TIMEOUT_MS);
    }

    /**
     * 截断错误信息。
     */
    private String truncate(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() <= MAX_ERROR_CHARS ? message : message.substring(0, MAX_ERROR_CHARS);
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
