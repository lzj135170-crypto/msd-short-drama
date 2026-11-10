package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import com.msd.content.support.JsonHttpSupport;
import com.msd.content.support.JsonHttpSupport.HttpResult;
import com.msd.content.support.RagUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用 DashScope Rerank 把 RRF Top N 精排到 Top K。
 *
 * <p>Rerank 失败、超时、未配置 Key 时回退 RRF 前 K 条，并标记 rerank 降级。
 * 默认使用用户原始 query 精排，检索阶段使用改写后的关键词。</p>
 */
public class DashScopeRerankPostProcessor implements DocumentPostProcessor {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankPostProcessor.class);
    /** 错误信息最大字符数。 */
    private static final int MAX_ERROR_CHARS = 500;

    // Rerank 配置
    private final RagProperties properties;
    // JSON 序列化器
    private final JsonMapper jsonMapper;
    // 复用的 HTTP 客户端
    private final HttpClient httpClient;

    // 失败退避截止时间
    private volatile long unavailableUntil;
    // 最近一次失败原因
    private volatile String lastError;

    /**
     * 注入 Rerank 配置、JSON 序列化器和 HTTP 客户端。
     */
    public DashScopeRerankPostProcessor(RagProperties properties,
                                        JsonMapper jsonMapper,
                                        HttpClient httpClient) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.httpClient = httpClient;
    }

    /**
     * 精排文档并写入 trace 的最终来源。
     */
    @Override
    public List<Document> process(Query query, List<Document> documents) {
        RagTrace trace = RagTrace.from(query);
        int topN = resolveTopN(trace);
        List<Document> safeDocuments = documents == null ? List.of() : documents;

        // 1. 没有候选或候选不超过 topN 时无需调用 Rerank
        if (safeDocuments.isEmpty()) {
            setFinalDocuments(trace, List.of());
            return List.of();
        }
        if (safeDocuments.size() <= topN) {
            setFinalDocuments(trace, safeDocuments);
            return safeDocuments;
        }

        // 2. 关闭 Rerank 时直接截断，不算降级
        if (!properties.getRerank().isEnabled()) {
            return fallback(trace, safeDocuments, topN);
        }
        if (isBlank(properties.getRerank().getApiKey())) {
            return degrade(trace, safeDocuments, topN, "未配置 Rerank API Key", true);
        }
        if (System.currentTimeMillis() < unavailableUntil) {
            return degrade(trace, safeDocuments, topN, "Rerank 退避中：" + lastError, false);
        }

        long startedAt = System.currentTimeMillis();
        try {
            // 3. 默认用原始 query 精排，避免改写关键词影响相关性判断
            String rerankQuery = resolveRerankQuery(query, trace);
            String body = requestJson(rerankQuery, safeDocuments);
            HttpResult result = JsonHttpSupport.send(
                    httpClient,
                    "POST",
                    properties.getRerank().getUrl(),
                    body,
                    properties.getRerank().getTimeoutMs(),
                    Map.of("Authorization", "Bearer " + properties.getRerank().getApiKey()));
            if (!result.successful()) {
                throw new RagUnavailableException("Rerank 请求失败：HTTP " + result.status()
                        + " " + truncate(result.body()));
            }

            // 4. 按 relevance_score 降序取 topN；全部低于阈值时视为无匹配
            List<Document> reranked = parseResults(result.body(), safeDocuments, topN);
            if (reranked.isEmpty()) {
                setFinalDocuments(trace, List.of());
                return List.of();
            }

            markSuccess();
            setFinalDocuments(trace, reranked);
            return reranked;
        } catch (Exception e) {
            String message = truncate(rootMessage(e));
            markFailure(message);
            if (trace != null) {
                trace.markDegraded("rerank");
            }
            log.warn("rerank degraded, fallback to rrf top {}: {}", topN, message);
            return fallback(trace, safeDocuments, topN);
        } finally {
            if (trace != null) {
                trace.setRerankMillis(System.currentTimeMillis() - startedAt);
            }
        }
    }

    /**
     * 最近一次失败原因。
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 读取请求指定的 topK，未指定时使用配置值。
     */
    private int resolveTopN(RagTrace trace) {
        int requested = trace == null ? 0 : trace.getRequestedTopK();
        int topN = requested > 0 ? requested : properties.getRerank().getTopN();
        return Math.max(topN, 1);
    }

    /**
     * 选择用于精排的 query。
     */
    private String resolveRerankQuery(Query query, RagTrace trace) {
        if (properties.getRerank().isUseRewrittenQuery()) {
            return query.text();
        }
        if (trace != null && !isBlank(trace.getOriginalQuery())) {
            return trace.getOriginalQuery();
        }
        return query.text();
    }

    /**
     * 构造 DashScope Rerank 请求体。
     */
    private String requestJson(String queryText, List<Document> documents) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("model", properties.getRerank().getModel());

        ObjectNode input = root.putObject("input");
        input.put("query", queryText == null ? "" : queryText);
        ArrayNode documentArray = input.putArray("documents");
        for (Document document : documents) {
            documentArray.add(document.getText() == null ? "" : document.getText());
        }

        root.putObject("parameters").put("return_documents", false);
        return jsonMapper.writeValueAsString(root);
    }

    /**
     * 解析 output.results 并按分数降序截断。
     */
    private List<Document> parseResults(String body, List<Document> documents, int topN) {
        JsonNode root = jsonMapper.readTree(body);
        JsonNode results = root.path("output").path("results");
        if (results.size() == 0) {
            throw new RagUnavailableException("Rerank 返回空结果");
        }

        // 1. 读取返回的 index 和 relevance_score，过滤越界和重复项
        List<RankedDocument> ranked = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            double score = item.path("relevance_score").asDouble(0.0);
            if (index < 0 || index >= documents.size() || !seen.add(index)
                    || score < properties.getRerank().getMinScore()) {
                continue;
            }
            ranked.add(new RankedDocument(index, score));
        }
        // 2. 按分数降序截断到 topN，并复制文档写入 rerankScore
        ranked.sort(Comparator.comparingDouble(RankedDocument::score).reversed());

        return ranked.stream()
                .limit(topN)
                .map(item -> withRerankScore(documents.get(item.index()), item.score()))
                .toList();
    }

    /**
     * 复制文档并写入 rerankScore。
     */
    private Document withRerankScore(Document document, double score) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        metadata.put(RagMetadata.RERANK_SCORE, score);
        return Document.builder()
                .id(document.getId())
                .text(document.getText())
                .metadata(metadata)
                .score(score)
                .build();
    }

    /**
     * 回退到 RRF 顺序的前 topN 条。
     */
    private List<Document> fallback(RagTrace trace, List<Document> documents, int topN) {
        int limit = Math.min(topN, documents.size());
        List<Document> result = List.copyOf(documents.subList(0, limit));
        setFinalDocuments(trace, result);
        return result;
    }

    /**
     * 标记降级并按需更新退避。
     */
    private List<Document> degrade(RagTrace trace, List<Document> documents, int topN,
                                   String reason, boolean updateBackoff) {
        if (updateBackoff) {
            markFailure(reason);
        }
        if (trace != null) {
            trace.markDegraded("rerank");
        }
        log.warn("rerank degraded, fallback to rrf top {}: {}", topN, reason);
        return fallback(trace, documents, topN);
    }

    /**
     * 写入最终来源。
     */
    private void setFinalDocuments(RagTrace trace, List<Document> documents) {
        if (trace != null) {
            trace.setFinalDocuments(documents);
        }
    }

    /**
     * 记录失败并进入退避。
     */
    private void markFailure(String message) {
        this.lastError = truncate(message);
        this.unavailableUntil = System.currentTimeMillis()
                + properties.getRerank().getFailureBackoffMs();
    }

    /**
     * 清除失败状态。
     */
    private void markSuccess() {
        this.lastError = null;
        this.unavailableUntil = 0;
    }

    /**
     * 判断字符串是否为空。
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    /**
     * Rerank 返回的单条排名。
     *
     * @param index 候选文档在请求中的下标
     * @param score Rerank 相关性分数
     */
    private record RankedDocument(int index, double score) {
    }
}
