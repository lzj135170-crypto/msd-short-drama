package com.msd.content.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单次 RAG 请求的追踪上下文。
 *
 * <p>通过 ChatClient advisor param 传入，RetrievalAugmentationAdvisor 会把它复制到
 * Query.context，因此改写器、检索器和精排器读到的是同一个实例。</p>
 */
public class RagTrace {

    /** advisor param 与 Query.context 共用的键。 */
    public static final String CONTEXT_KEY = "msd.rag.trace";

    // 用户原始查询
    private final String originalQuery;
    // 请求开始时间（毫秒时间戳）
    private final long startedAt;

    // 改写后的查询，未改写或失败时为 null
    private String rewrittenQuery;
    // 改写使用的模型，null 表示沿用默认模型
    private String rewriteModel;
    // 改写是否失败
    private boolean rewriteFailed;
    // 改写耗时（毫秒）
    private long rewriteMillis;

    // 向量检索命中数，多查询并发时累加
    private final LongAdder vectorHits = new LongAdder();
    // BM25 检索命中数，多查询并发时累加
    private final LongAdder bm25Hits = new LongAdder();
    // RRF 融合后的文档数，多查询并发时累加
    private final LongAdder rrfHits = new LongAdder();
    // 向量检索耗时（毫秒），多查询并发时累加
    private final LongAdder vectorMillis = new LongAdder();
    // BM25 检索耗时（毫秒），多查询并发时累加
    private final LongAdder bm25Millis = new LongAdder();

    // 当前请求的用户 ID
    private Long userId;
    // 由画像生成的偏好检索词
    private String preferenceQuery;
    // 是否没有可用画像
    private boolean personalizationEmpty;
    // 多查询扩展后的检索词，按执行顺序记录
    private final List<String> expandedQueries = Collections.synchronizedList(new ArrayList<>());

    // Rerank 耗时（毫秒）
    private long rerankMillis;
    // 请求指定的返回条数，0 表示使用配置默认值
    private int requestedTopK;
    // 最终返回给用户的文档
    private List<Document> finalDocuments = List.of();

    // 本次请求发生的降级标记，集合本身线程安全
    private final Set<String> degradedFlags = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * 创建一次请求的追踪对象。
     */
    public RagTrace(String originalQuery) {
        this.originalQuery = originalQuery;
        this.startedAt = System.currentTimeMillis();
    }

    /**
     * 从 Query.context 中取回追踪对象，缺失时返回 null。
     */
    public static RagTrace from(Query query) {
        if (query == null || query.context() == null) {
            return null;
        }
        Object value = query.context().get(CONTEXT_KEY);
        return value instanceof RagTrace trace ? trace : null;
    }

    /**
     * 记录降级标记，重复标记自动去重。
     */
    public void markDegraded(String flag) {
        degradedFlags.add(flag);
    }

    /**
     * 当前请求是否发生过降级。
     */
    public boolean isDegraded() {
        return !degradedFlags.isEmpty();
    }

    /** 用户原始查询。 */
    public String getOriginalQuery() {
        return originalQuery;
    }

    /** 请求开始时间（毫秒时间戳）。 */
    public long getStartedAt() {
        return startedAt;
    }

    /** 改写后的查询，未改写或失败时为 null。 */
    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    /** 记录改写后的查询。 */
    public void setRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    /** 改写使用的模型，null 表示沿用默认模型。 */
    public String getRewriteModel() {
        return rewriteModel;
    }

    /** 记录改写使用的模型。 */
    public void setRewriteModel(String rewriteModel) {
        this.rewriteModel = rewriteModel;
    }

    /** 改写是否失败。 */
    public boolean isRewriteFailed() {
        return rewriteFailed;
    }

    /** 记录改写失败状态。 */
    public void setRewriteFailed(boolean rewriteFailed) {
        this.rewriteFailed = rewriteFailed;
    }

    /** 改写耗时（毫秒）。 */
    public long getRewriteMillis() {
        return rewriteMillis;
    }

    /** 记录改写耗时（毫秒）。 */
    public void setRewriteMillis(long rewriteMillis) {
        this.rewriteMillis = rewriteMillis;
    }

    /** 向量检索命中数。 */
    public int getVectorHits() {
        return vectorHits.intValue();
    }

    /** 累加向量检索命中数，供多查询并发调用。 */
    public void addVectorHits(int hits) {
        vectorHits.add(Math.max(hits, 0));
    }

    /** BM25 检索命中数。 */
    public int getBm25Hits() {
        return bm25Hits.intValue();
    }

    /** 累加 BM25 检索命中数，供多查询并发调用。 */
    public void addBm25Hits(int hits) {
        bm25Hits.add(Math.max(hits, 0));
    }

    /** RRF 融合后的文档数。 */
    public int getRrfHits() {
        return rrfHits.intValue();
    }

    /** 累加 RRF 融合后的文档数，供多查询并发调用。 */
    public void addRrfHits(int hits) {
        rrfHits.add(Math.max(hits, 0));
    }

    /** 向量检索耗时（毫秒）。 */
    public long getVectorMillis() {
        return vectorMillis.longValue();
    }

    /** 累加向量检索耗时，供多查询并发调用。 */
    public void addVectorMillis(long millis) {
        vectorMillis.add(Math.max(millis, 0));
    }

    /** BM25 检索耗时（毫秒）。 */
    public long getBm25Millis() {
        return bm25Millis.longValue();
    }

    /** 累加 BM25 检索耗时，供多查询并发调用。 */
    public void addBm25Millis(long millis) {
        bm25Millis.add(Math.max(millis, 0));
    }

    /** 当前请求用户 ID。 */
    public Long getUserId() {
        return userId;
    }

    /** 记录当前请求用户 ID。 */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** 画像生成的偏好检索词。 */
    public String getPreferenceQuery() {
        return preferenceQuery;
    }

    /** 记录偏好检索词。 */
    public void setPreferenceQuery(String preferenceQuery) {
        this.preferenceQuery = preferenceQuery;
    }

    /** 是否没有可用画像。 */
    public boolean isPersonalizationEmpty() {
        return personalizationEmpty;
    }

    /** 记录画像是否为空。 */
    public void setPersonalizationEmpty(boolean personalizationEmpty) {
        this.personalizationEmpty = personalizationEmpty;
    }

    /** 多查询扩展后的检索词。 */
    public List<String> getExpandedQueries() {
        synchronized (expandedQueries) {
            return List.copyOf(expandedQueries);
        }
    }

    /** 记录多查询扩展后的检索词。 */
    public void setExpandedQueries(List<String> queries) {
        synchronized (expandedQueries) {
            expandedQueries.clear();
            if (queries != null) {
                expandedQueries.addAll(queries);
            }
        }
    }

    /** Rerank 耗时（毫秒）。 */
    public long getRerankMillis() {
        return rerankMillis;
    }

    /** 记录 Rerank 耗时（毫秒）。 */
    public void setRerankMillis(long rerankMillis) {
        this.rerankMillis = rerankMillis;
    }

    /** 请求指定的返回条数，0 表示使用配置默认值。 */
    public int getRequestedTopK() {
        return requestedTopK;
    }

    /** 记录请求指定的返回条数。 */
    public void setRequestedTopK(int requestedTopK) {
        this.requestedTopK = requestedTopK;
    }

    /** 最终返回给用户的文档。 */
    public List<Document> getFinalDocuments() {
        return finalDocuments;
    }

    /** 记录最终文档，null 转为空列表并做不可变拷贝。 */
    public void setFinalDocuments(List<Document> finalDocuments) {
        this.finalDocuments = finalDocuments == null ? List.of() : List.copyOf(finalDocuments);
    }

    /**
     * 复制降级标记，避免调用方修改内部集合。
     */
    public List<String> getDegradedFlags() {
        synchronized (degradedFlags) {
            return List.copyOf(degradedFlags);
        }
    }
}
