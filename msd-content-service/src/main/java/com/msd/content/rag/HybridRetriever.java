package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import com.msd.content.support.LazyMilvusStoreProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 向量检索和 BM25 并行执行，再用 RRF 融合。
 *
 * <p>两路各自限时，单路失败或超时只标记降级，另一路结果仍然返回。</p>
 */
public class HybridRetriever implements DocumentRetriever {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
    /** 日志中查询文本的预览长度。 */
    private static final int PREVIEW_CHARS = 100;

    // RAG 配置
    private final RagProperties properties;
    // 向量库延迟提供者
    private final LazyMilvusStoreProvider milvusProvider;
    // BM25 检索存储
    private final ElasticsearchBm25Store bm25Store;
    // RRF 融合器
    private final RrfMerger rrfMerger;
    // 两路并行检索线程池
    private final Executor retrievalExecutor;

    /**
     * 注入两路检索器、融合器和独立线程池。
     */
    public HybridRetriever(RagProperties properties,
                           LazyMilvusStoreProvider milvusProvider,
                           ElasticsearchBm25Store bm25Store,
                           RrfMerger rrfMerger,
                           Executor retrievalExecutor) {
        this.properties = properties;
        this.milvusProvider = milvusProvider;
        this.bm25Store = bm25Store;
        this.rrfMerger = rrfMerger;
        this.retrievalExecutor = retrievalExecutor;
    }

    /**
     * 并行检索并融合，默认只检索短剧文档。
     */
    @Override
    public List<Document> retrieve(Query query) {
        return retrieve(query, RetrievalScope.drama());
    }

    /**
     * 按指定范围并行检索并融合。
     */
    public List<Document> retrieve(Query query, RetrievalScope scope) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }

        String text = query.text();
        RetrievalScope safeScope = scope == null ? RetrievalScope.drama() : scope;
        RagTrace trace = RagTrace.from(query);

        // 1. 两路并行；关闭的分支直接返回空结果
        CompletableFuture<BranchResult> vectorFuture = properties.getVector().isEnabled()
                ? CompletableFuture.supplyAsync(() -> vectorBranch(text, safeScope), retrievalExecutor)
                : CompletableFuture.completedFuture(BranchResult.empty());
        CompletableFuture<BranchResult> bm25Future = properties.getBm25().isEnabled()
                ? CompletableFuture.supplyAsync(() -> bm25Branch(text, safeScope), retrievalExecutor)
                : CompletableFuture.completedFuture(BranchResult.empty());

        // 2. 每路独立超时，单路异常不影响另一路
        BranchResult vector = await(vectorFuture, properties.getVector().getTimeoutMs(), "vector");
        BranchResult bm25 = await(bm25Future, properties.getBm25().getTimeoutMs(), "bm25");

        // 3. 主线程统一写 trace，避免分支线程共享可变状态
        if (trace != null) {
            trace.addVectorHits(vector.documents().size());
            trace.addVectorMillis(vector.millis());
            trace.addBm25Hits(bm25.documents().size());
            trace.addBm25Millis(bm25.millis());
            if (vector.error() != null) {
                trace.markDegraded("vector");
            }
            if (bm25.error() != null) {
                trace.markDegraded("bm25");
            }
        }
        if (vector.error() != null) {
            log.warn("vector retrieval degraded: {}", vector.error());
        }
        if (bm25.error() != null) {
            log.warn("bm25 retrieval degraded: {}", bm25.error());
        }

        // 4. RRF 融合并截断；多查询时先按单 query 的召回上限裁剪
        List<Document> merged = rrfMerger.merge(
                vector.documents(),
                bm25.documents(),
                properties.getHybrid().getRrfK(),
                retrievalTopK(properties.getHybrid().getTopK()));
        if (trace != null) {
            trace.addRrfHits(merged.size());
        }

        log.info("hybrid retrieval: query='{}', episodeScope={}, vectorHits={}, bm25Hits={}, "
                        + "rrfHits={}, vectorMs={}, bm25Ms={}",
                preview(text), safeScope.episodeScope(), vector.documents().size(),
                bm25.documents().size(), merged.size(), vector.millis(), bm25.millis());
        return merged;
    }

    /**
     * 检索字幕片段，限定短剧和最大集数；两路并行后 RRF 融合。
     */
    public List<Document> retrieveTranscripts(Query query, Long dramaId, Integer maxEpisodeNo) {
        if (query == null || query.text() == null || query.text().isBlank()
                || dramaId == null || maxEpisodeNo == null || maxEpisodeNo <= 0) {
            return List.of();
        }
        String text = query.text();
        RagTrace trace = RagTrace.from(query);
        CompletableFuture<BranchResult> vectorFuture = properties.getVector().isEnabled()
                ? CompletableFuture.supplyAsync(() -> transcriptVectorBranch(
                        text, dramaId, maxEpisodeNo), retrievalExecutor)
                : CompletableFuture.completedFuture(BranchResult.empty());
        CompletableFuture<BranchResult> bm25Future = properties.getBm25().isEnabled()
                ? CompletableFuture.supplyAsync(() -> transcriptBm25Branch(
                        text, dramaId, maxEpisodeNo), retrievalExecutor)
                : CompletableFuture.completedFuture(BranchResult.empty());

        BranchResult vector = await(vectorFuture, properties.getVector().getTimeoutMs(), "vector");
        BranchResult bm25 = await(bm25Future, properties.getBm25().getTimeoutMs(), "bm25");
        if (trace != null) {
            trace.addVectorHits(vector.documents().size());
            trace.addVectorMillis(vector.millis());
            trace.addBm25Hits(bm25.documents().size());
            trace.addBm25Millis(bm25.millis());
            if (vector.error() != null) {
                trace.markDegraded("vector");
            }
            if (bm25.error() != null) {
                trace.markDegraded("bm25");
            }
        }
        List<Document> merged = rrfMerger.merge(
                vector.documents(),
                bm25.documents(),
                properties.getHybrid().getRrfK(),
                retrievalTopK(properties.getHybrid().getTopK()));
        if (trace != null) {
            trace.addRrfHits(merged.size());
        }
        log.info("transcript retrieval: query='{}', dramaId={}, maxEpisodeNo={}, "
                        + "vectorHits={}, bm25Hits={}, rrfHits={}",
                preview(text), dramaId, maxEpisodeNo, vector.documents().size(),
                bm25.documents().size(), merged.size());
        return merged;
    }

    /**
     * 字幕向量检索分支。
     */
    private BranchResult transcriptVectorBranch(String text, Long dramaId, Integer maxEpisodeNo) {
        long startedAt = System.currentTimeMillis();
        try {
            return new BranchResult(milvusProvider.searchTranscripts(
                    text, dramaId, maxEpisodeNo, retrievalTopK(properties.getVector().getTopK())),
                    System.currentTimeMillis() - startedAt, null);
        } catch (Exception e) {
            return new BranchResult(List.of(), System.currentTimeMillis() - startedAt,
                    rootMessage(e));
        }
    }

    /**
     * 字幕 BM25 检索分支。
     */
    private BranchResult transcriptBm25Branch(String text, Long dramaId, Integer maxEpisodeNo) {
        long startedAt = System.currentTimeMillis();
        try {
            return new BranchResult(bm25Store.searchTranscripts(
                    text, dramaId, maxEpisodeNo, retrievalTopK(properties.getBm25().getTopK())),
                    System.currentTimeMillis() - startedAt, null);
        } catch (Exception e) {
            return new BranchResult(List.of(), System.currentTimeMillis() - startedAt,
                    rootMessage(e));
        }
    }

    /**
     * 向量检索分支，按范围选择短剧或分集检索，异常转成降级结果。
     */
    private BranchResult vectorBranch(String text, RetrievalScope scope) {
        long startedAt = System.currentTimeMillis();
        try {
            int topK = retrievalTopK(properties.getVector().getTopK());
            List<Document> documents = scope.episodeScope()
                    ? milvusProvider.searchEpisodes(
                            text, scope.dramaId(), scope.beforeEpisodeNo(), topK)
                    : milvusProvider.search(text, topK);
            return new BranchResult(documents == null ? List.of() : documents,
                    System.currentTimeMillis() - startedAt, null);
        } catch (Exception e) {
            return new BranchResult(List.of(), System.currentTimeMillis() - startedAt,
                    rootMessage(e));
        }
    }

    /**
     * BM25 检索分支，按范围选择短剧或分集检索，异常转成降级结果。
     */
    private BranchResult bm25Branch(String text, RetrievalScope scope) {
        long startedAt = System.currentTimeMillis();
        try {
            int topK = retrievalTopK(properties.getBm25().getTopK());
            List<Document> documents = scope.episodeScope()
                    ? bm25Store.searchEpisodes(
                            text, scope.dramaId(), scope.beforeEpisodeNo(), topK)
                    : bm25Store.search(text, topK);
            return new BranchResult(documents == null ? List.of() : documents,
                    System.currentTimeMillis() - startedAt, null);
        } catch (Exception e) {
            return new BranchResult(List.of(), System.currentTimeMillis() - startedAt,
                    rootMessage(e));
        }
    }

    /**
     * 多查询扩展开启时，每条 query 只召回配置的条数，控制下游成本。
     */
    private int retrievalTopK(int configured) {
        if (properties.getExpansion() != null && properties.getExpansion().isEnabled()) {
            return Math.max(properties.getExpansion().getPerQueryTopK(), 1);
        }
        return Math.max(configured, 1);
    }

    /**
     * 等待单个分支完成，超时或异常都转成降级结果。
     */
    private BranchResult await(CompletableFuture<BranchResult> future, long timeoutMs,
                               String branch) {
        try {
            return future.get(Math.max(timeoutMs, 100), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new BranchResult(List.of(), timeoutMs, branch + " 检索超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BranchResult(List.of(), 0, branch + " 检索被中断");
        } catch (ExecutionException e) {
            return new BranchResult(List.of(), 0, rootMessage(e.getCause()));
        }
    }

    /**
     * 截断日志中的查询文本。
     */
    private String preview(String text) {
        return text.length() <= PREVIEW_CHARS ? text : text.substring(0, PREVIEW_CHARS);
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
     * 单路检索结果。
     *
     * @param documents 该路命中的文档
     * @param millis    该路耗时（毫秒）
     * @param error     降级原因，正常时为 null
     */
    private record BranchResult(List<Document> documents, long millis, String error) {

        /**
         * 分支关闭时的空结果。
         */
        private static BranchResult empty() {
            return new BranchResult(List.of(), 0, null);
        }
    }
}
