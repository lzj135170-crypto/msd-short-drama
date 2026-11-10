package com.msd.content.index;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.DramaChangedEvent;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.IndexUpdateResult;
import com.msd.content.rag.Bm25IndexStore;
import com.msd.content.support.LazyMilvusStoreProvider;
import com.msd.content.support.RagIndexException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DRAMA_CHANGED 增量索引：单剧 upsert 或按 dramaId 删除。
 *
 * <p>UPSERT 以短剧导出接口的当前状态为准：短剧已不可见时自动降级为删除。
 * 向量和 BM25 两路互不影响；任一路失败会抛出，交给调度器重试，全量重建兜底。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class IncrementalIndexService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(IncrementalIndexService.class);
    /** 错误信息最大字符数。 */
    private static final int MAX_ERROR_CHARS = 300;

    // 短剧目录导出客户端
    private final DramaCatalogClient catalogClient;
    // 文档构建器
    private final DramaDocumentBuilder documentBuilder;
    // 向量库延迟提供者
    private final ObjectProvider<LazyMilvusStoreProvider> milvusProvider;
    // BM25 存储，允许实现缺失
    private final ObjectProvider<Bm25IndexStore> bm25StoreProvider;
    // RAG 配置
    private final RagProperties properties;
    // Micrometer 注册表，测试可传 null
    private final MeterRegistry meterRegistry;

    /**
     * 注入目录客户端、文档构建器、两个索引存储、配置和指标。
     */
    public IncrementalIndexService(DramaCatalogClient catalogClient,
                                   DramaDocumentBuilder documentBuilder,
                                   ObjectProvider<LazyMilvusStoreProvider> milvusProvider,
                                   ObjectProvider<Bm25IndexStore> bm25StoreProvider,
                                   RagProperties properties,
                                   MeterRegistry meterRegistry) {
        this.catalogClient = catalogClient;
        this.documentBuilder = documentBuilder;
        this.milvusProvider = milvusProvider;
        this.bm25StoreProvider = bm25StoreProvider;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 处理一条短剧变更事件。
     *
     * @throws RagIndexException 目录不可用或任一路索引失败
     */
    public IndexUpdateResult apply(DramaChangedEvent event) {
        long startedAt = System.currentTimeMillis();
        try {
            IndexUpdateResult result = doApply(event, startedAt);
            recordSuccess(event, result, startedAt);
            return result;
        } catch (RuntimeException e) {
            recordFailure(event);
            throw e;
        }
    }

    /**
     * 执行实际索引动作。
     */
    private IndexUpdateResult doApply(DramaChangedEvent event, long startedAt) {
        // 1. 校验事件
        if (event == null || event.dramaId() == null || event.dramaId() <= 0) {
            throw new RagIndexException("短剧变更事件缺少 dramaId");
        }
        Long dramaId = event.dramaId();

        // 2. 删除事件直接清理两路索引
        if (DramaChangedAction.DELETE == event.action()) {
            return deleteDrama(dramaId, startedAt);
        }

        // 3. UPSERT 以当前导出状态为准，短剧已不可见时转为删除
        DramaRagExportDTO drama = fetchDrama(dramaId);
        if (drama == null) {
            log.info("incremental index drama no longer visible, fallback to delete: dramaId={}",
                    dramaId);
            return deleteDrama(dramaId, startedAt);
        }

        // 4. 构建短剧文档和分集文档
        List<Document> documents = buildDocuments(drama);
        int episodeCount = documents.size() - 1;
        List<String> errors = new ArrayList<>();

        // 5. 两路索引互不影响，任一失败先记录再统一抛出触发重试
        boolean vectorUpdated = upsertVector(dramaId, documents, errors);
        boolean bm25Updated = upsertBm25(drama, errors);

        IndexUpdateResult result = new IndexUpdateResult(
                dramaId, false, episodeCount, vectorUpdated, bm25Updated,
                List.copyOf(errors), System.currentTimeMillis() - startedAt);
        if (!errors.isEmpty()) {
            throw new RagIndexException("增量索引部分失败：dramaId=" + dramaId
                    + ", errors=" + String.join("; ", errors));
        }
        log.info("incremental index upsert: dramaId={}, episodes={}, vector={}, bm25={}, "
                        + "elapsedMs={}",
                dramaId, episodeCount, vectorUpdated, bm25Updated, result.elapsedMs());
        return result;
    }

    /**
     * 拉取单部短剧导出数据；下游失败直接抛出触发重试。
     */
    private DramaRagExportDTO fetchDrama(Long dramaId) {
        try {
            Result<DramaRagExportDTO> response = catalogClient.detail(dramaId);
            if (response == null || !response.isSuccess()) {
                String message = response == null ? "无响应" : response.getMessage();
                throw new RagIndexException("短剧导出失败：" + message);
            }
            return response.getData();
        } catch (RagIndexException e) {
            throw e;
        } catch (Exception e) {
            throw new RagIndexException("短剧导出调用失败：" + rootMessage(e), e);
        }
    }

    /**
     * 构建短剧和分集文档，跳过分集缺失的记录。
     */
    private List<Document> buildDocuments(DramaRagExportDTO drama) {
        List<Document> documents = new ArrayList<>();
        documents.add(documentBuilder.build(drama));
        if (drama.episodes() == null) {
            return documents;
        }
        for (EpisodeRagExportDTO episode : drama.episodes()) {
            if (episode != null && episode.id() != null) {
                documents.add(documentBuilder.buildEpisode(drama, episode));
            }
        }
        return documents;
    }

    /**
     * 先按 dramaId 删除旧向量，再分批写入新向量。
     */
    private boolean upsertVector(Long dramaId, List<Document> documents, List<String> errors) {
        if (!properties.getVector().isEnabled()) {
            return true;
        }
        LazyMilvusStoreProvider provider = milvusProvider.getIfAvailable();
        if (provider == null) {
            errors.add("vector: Milvus 提供者不存在");
            return false;
        }
        try {
            provider.deleteByDrama(dramaId);
            MilvusVectorStore store = provider.get();
            int batchSize = Math.max(properties.getVector().getBatchSize(), 1);
            for (int from = 0; from < documents.size(); from += batchSize) {
                int to = Math.min(from + batchSize, documents.size());
                store.add(documents.subList(from, to));
            }
            return true;
        } catch (Exception e) {
            errors.add("vector: " + truncateError(rootMessage(e)));
            return false;
        }
    }

    /**
     * 先按 dramaId 删除旧 BM25 文档，再写入当前短剧文档并刷新。
     */
    private boolean upsertBm25(DramaRagExportDTO drama, List<String> errors) {
        if (!properties.getBm25().isEnabled()) {
            return true;
        }
        Bm25IndexStore store = bm25StoreProvider.getIfAvailable();
        if (store == null) {
            errors.add("bm25: BM25 索引实现不存在");
            return false;
        }
        try {
            // 索引不存在时 ping=false；不能直接 PUT，否则 ES 会用动态 mapping 自动建索引
            if (!store.ping()) {
                errors.add("bm25: 索引不可用，请先执行全量 reindex");
                return false;
            }
            store.deleteByDrama(drama.id());
            store.indexAll(List.of(drama));
            store.refresh();
            return true;
        } catch (Exception e) {
            errors.add("bm25: " + truncateError(rootMessage(e)));
            return false;
        }
    }

    /**
     * 删除某部短剧的两路索引。
     */
    private IndexUpdateResult deleteDrama(Long dramaId, long startedAt) {
        List<String> errors = new ArrayList<>();
        boolean vectorUpdated = deleteVector(dramaId, errors);
        boolean bm25Updated = deleteBm25(dramaId, errors);

        IndexUpdateResult result = new IndexUpdateResult(
                dramaId, true, 0, vectorUpdated, bm25Updated,
                List.copyOf(errors), System.currentTimeMillis() - startedAt);
        if (!errors.isEmpty()) {
            throw new RagIndexException("增量删除部分失败：dramaId=" + dramaId
                    + ", errors=" + String.join("; ", errors));
        }
        log.info("incremental index delete: dramaId={}, vector={}, bm25={}, elapsedMs={}",
                dramaId, vectorUpdated, bm25Updated, result.elapsedMs());
        return result;
    }

    /**
     * 删除 Milvus 中该短剧的全部文档。
     */
    private boolean deleteVector(Long dramaId, List<String> errors) {
        if (!properties.getVector().isEnabled()) {
            return true;
        }
        LazyMilvusStoreProvider provider = milvusProvider.getIfAvailable();
        if (provider == null) {
            errors.add("vector: Milvus 提供者不存在");
            return false;
        }
        try {
            provider.deleteByDrama(dramaId);
            return true;
        } catch (Exception e) {
            errors.add("vector: " + truncateError(rootMessage(e)));
            return false;
        }
    }

    /**
     * 删除 Elasticsearch 中该短剧的全部文档。
     */
    private boolean deleteBm25(Long dramaId, List<String> errors) {
        if (!properties.getBm25().isEnabled()) {
            return true;
        }
        Bm25IndexStore store = bm25StoreProvider.getIfAvailable();
        if (store == null) {
            errors.add("bm25: BM25 索引实现不存在");
            return false;
        }
        try {
            store.deleteByDrama(dramaId);
            return true;
        } catch (Exception e) {
            errors.add("bm25: " + truncateError(rootMessage(e)));
            return false;
        }
    }

    /**
     * 记录成功计数和索引延迟。
     */
    private void recordSuccess(DramaChangedEvent event, IndexUpdateResult result,
                               long startedAt) {
        if (meterRegistry == null || event == null) {
            return;
        }
        String action = event.action() == null ? "UNKNOWN" : event.action().name();
        meterRegistry.counter("rag_index_upsert_total", "action", action).increment();
        long lagMs = event.occurredAt() > 0
                ? Math.max(System.currentTimeMillis() - event.occurredAt(), 0) : 0;
        meterRegistry.timer("rag_index_lag", "action", action)
                .record(lagMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录失败计数。
     */
    private void recordFailure(DramaChangedEvent event) {
        if (meterRegistry == null || event == null) {
            return;
        }
        String action = event.action() == null ? "UNKNOWN" : event.action().name();
        meterRegistry.counter("rag_index_failure_total", "action", action).increment();
    }

    /**
     * 截断错误信息，避免日志无限增长。
     */
    private String truncateError(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() <= MAX_ERROR_CHARS
                ? message : message.substring(0, MAX_ERROR_CHARS);
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
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
