package com.msd.content.index;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.ReindexResult;
import com.msd.content.dto.TranscriptReindexResult;
import com.msd.content.rag.Bm25IndexStore;
import com.msd.content.support.LazyMilvusStoreProvider;
import com.msd.content.support.RagConflictException;
import com.msd.content.support.RagIndexException;
import com.msd.content.support.RagIndexState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 短剧索引重建：从 msd-drama 导出全量目录，写入 Milvus 和 BM25。
 *
 * <p>全量重建用 ReentrantLock 防止并发；目录导出失败时不改动已有索引。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DramaIndexService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DramaIndexService.class);
    /** 错误信息最大字符数。 */
    private static final int MAX_ERROR_CHARS = 300;

    // 短剧目录导出客户端
    private final DramaCatalogClient catalogClient;
    // 文档构建器
    private final DramaDocumentBuilder documentBuilder;
    // 向量库延迟提供者
    private final LazyMilvusStoreProvider milvusProvider;
    // BM25 存储，允许实现缺失
    private final ObjectProvider<Bm25IndexStore> bm25StoreProvider;
    // RAG 配置
    private final RagProperties properties;
    // 最近一次索引状态
    private final RagIndexState indexState;
    // 字幕索引服务，全量重建后补建 transcript
    private final TranscriptIndexService transcriptIndexService;

    // 全量重建互斥锁
    private final ReentrantLock lock = new ReentrantLock();

    /** 上一次成功写入 Milvus 的文档 ID，用于删除已下线短剧。 */
    private volatile Set<String> indexedVectorIds = Set.of();

    /**
     * 注入目录客户端、文档构建器、两个索引存储和状态对象。
     */
    public DramaIndexService(DramaCatalogClient catalogClient,
                             DramaDocumentBuilder documentBuilder,
                             LazyMilvusStoreProvider milvusProvider,
                             ObjectProvider<Bm25IndexStore> bm25StoreProvider,
                             RagProperties properties,
                             RagIndexState indexState,
                             TranscriptIndexService transcriptIndexService) {
        this.catalogClient = catalogClient;
        this.documentBuilder = documentBuilder;
        this.milvusProvider = milvusProvider;
        this.bm25StoreProvider = bm25StoreProvider;
        this.properties = properties;
        this.indexState = indexState;
        this.transcriptIndexService = transcriptIndexService;
    }

    /**
     * 全量重建索引。
     *
     * @param allowEmptyCatalog 目录为空时是否允许清空已有索引
     * @throws RagConflictException 已有重建任务在执行
     * @throws RagIndexException    短剧目录导出失败，或空目录被拒绝
     */
    public ReindexResult reindex(boolean allowEmptyCatalog) {
        // 1. 同一时间只允许一个重建任务
        if (!lock.tryLock()) {
            throw new RagConflictException("索引重建正在进行中，请稍后重试");
        }
        long startedAt = System.currentTimeMillis();
        try {
            // 2. 先导出完整目录；失败时直接抛出，已有索引保持不变
            List<DramaRagExportDTO> dramas = fetchCatalog();
            if (dramas.isEmpty() && !allowEmptyCatalog && indexState.getLastDramaCount() > 0) {
                throw new RagIndexException(
                        "短剧目录为空，拒绝清空现有索引；如确认清空请传 allowEmptyCatalog=true");
            }

            // 3. 同时构建短剧文档和分集文档，两类文档共用一套 ID 规则
            List<Document> documents = buildDocuments(dramas);
            int episodeCount = documents.size() - dramas.size();

            // 4. 两路索引互不影响，任一路失败只标记 partial
            List<String> errors = new ArrayList<>();
            VectorOutcome vector = rebuildVector(documents);
            if (vector.error() != null) {
                errors.add("vector: " + vector.error());
            }
            Bm25Outcome bm25 = rebuildBm25(dramas);
            if (bm25.error() != null) {
                errors.add("bm25: " + bm25.error());
            }

            // 5. 全量重建会重建 ES index，必须补建字幕索引；单集失败只标记 partial
            rebuildTranscripts(errors);

            boolean partial = !vector.available() || !bm25.available()
                    || !errors.isEmpty();
            ReindexResult result = new ReindexResult(
                    dramas.size(),
                    episodeCount,
                    vector.upserted(),
                    bm25.indexed(),
                    vector.staleDeleted(),
                    vector.available(),
                    bm25.available(),
                    partial,
                    List.copyOf(errors),
                    System.currentTimeMillis() - startedAt);

            // 5. 保存最近一次结果，供状态接口读取
            indexState.record(result);
            log.info("rag reindex finished: dramaCount={}, episodeCount={}, vectorUpserted={}, "
                            + "esIndexed={}, staleDeleted={}, vectorAvailable={}, bm25Available={}, "
                            + "partial={}, elapsedMs={}",
                    result.dramaCount(), result.episodeCount(), result.vectorUpserted(),
                    result.esIndexed(), result.staleDeleted(), result.vectorAvailable(),
                    result.bm25Available(), result.partial(), result.elapsedMs());
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 分页拉取全量已上架短剧，按 ID 去重并保持顺序。
     */
    private List<DramaRagExportDTO> fetchCatalog() {
        int pageSize = Math.max(properties.getCatalog().getPageSize(), 1);
        int maxPages = Math.max(properties.getCatalog().getMaxPages(), 1);
        Map<Long, DramaRagExportDTO> byId = new LinkedHashMap<>();

        // 1. 逐页拉取目录，直到空页或不足一页
        for (int page = 1; page <= maxPages; page++) {
            Result<PageResult<DramaRagExportDTO>> response;
            try {
                response = catalogClient.export(page, pageSize);
            } catch (Exception e) {
                throw new RagIndexException("短剧目录导出调用失败：" + rootMessage(e), e);
            }
            if (response == null || !response.isSuccess() || response.getData() == null) {
                String message = response == null ? "无响应" : response.getMessage();
                throw new RagIndexException("短剧目录导出失败：" + message);
            }

            PageResult<DramaRagExportDTO> data = response.getData();
            List<DramaRagExportDTO> records = data.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            // 2. 按 ID 去重，保留首次出现的记录
            for (DramaRagExportDTO drama : records) {
                if (drama != null && drama.id() != null) {
                    byId.putIfAbsent(drama.id(), drama);
                }
            }
            // 最后一页不足 pageSize，说明目录已经取完
            if (records.size() < pageSize) {
                break;
            }
            if (page == maxPages) {
                log.warn("rag catalog reached max pages: maxPages={}, loaded={}", maxPages, byId.size());
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * 构建短剧文档和分集文档，跳过没有 ID 的分集。
     */
    private List<Document> buildDocuments(List<DramaRagExportDTO> dramas) {
        List<Document> documents = new ArrayList<>();
        for (DramaRagExportDTO drama : dramas) {
            // 1. 每部短剧始终生成一个短剧文档
            documents.add(documentBuilder.build(drama));
            // 2. 分集明细存在时逐集生成 episode-{id} 文档
            if (drama.episodes() == null) {
                continue;
            }
            for (EpisodeRagExportDTO episode : drama.episodes()) {
                if (episode != null && episode.id() != null) {
                    documents.add(documentBuilder.buildEpisode(drama, episode));
                }
            }
        }
        return documents;
    }

    /**
     * 重建 Milvus 向量索引。
     *
     * <p>Spring AI 的 add 底层是 insert 而不是 upsert，因此必须先删除上一轮和本轮的 ID。</p>
     */
    private VectorOutcome rebuildVector(List<Document> documents) {
        if (!properties.getVector().isEnabled()) {
            return new VectorOutcome(true, 0, 0, null);
        }

        Set<String> currentIds = documents.stream()
                .map(Document::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> previousIds = indexedVectorIds;
        Set<String> idsToDelete = new LinkedHashSet<>(previousIds);
        idsToDelete.addAll(currentIds);

        try {
            // 1. 延迟获取 store，Milvus 未启动时只影响本分支
            MilvusVectorStore store = milvusProvider.get();

            // 2. 删除上一轮和本轮 ID，避免重复数据
            if (!idsToDelete.isEmpty()) {
                store.delete(new ArrayList<>(idsToDelete));
            }

            // 3. 分批写入；embedding 接口对单批文档数有限制，失败会在此抛出
            int batchSize = Math.max(properties.getVector().getBatchSize(), 1);
            for (int from = 0; from < documents.size(); from += batchSize) {
                int to = Math.min(from + batchSize, documents.size());
                store.add(documents.subList(from, to));
            }

            indexedVectorIds = currentIds;
            int staleDeleted = (int) previousIds.stream()
                    .filter(id -> !currentIds.contains(id))
                    .count();
            return new VectorOutcome(true, documents.size(), staleDeleted, null);
        } catch (Exception e) {
            String message = truncateError(rootMessage(e));
            log.warn("rag vector reindex failed: {}", message);
            return new VectorOutcome(false, 0, 0, message);
        }
    }

    /**
     * 重建 BM25 索引；实现尚未接入时标记为不可用。
     */
    private Bm25Outcome rebuildBm25(List<DramaRagExportDTO> dramas) {
        if (!properties.getBm25().isEnabled()) {
            return new Bm25Outcome(true, 0, null);
        }
        Bm25IndexStore store = bm25StoreProvider.getIfAvailable();
        if (store == null) {
            return new Bm25Outcome(false, 0, "BM25 索引实现尚未接入");
        }
        try {
            // 1. 删除并重建索引结构
            store.recreateIndex();
            // 2. 全量写入文档
            int indexed = store.indexAll(dramas);
            // 3. 刷新，保证重建后立即可查
            store.refresh();
            return new Bm25Outcome(true, indexed, null);
        } catch (Exception e) {
            String message = truncateError(rootMessage(e));
            log.warn("rag bm25 reindex failed: {}", message);
            return new Bm25Outcome(false, 0, message);
        }
    }

    /**
     * 补建字幕索引；列表或单集失败都写入 errors，不中断主索引结果。
     */
    private TranscriptReindexResult rebuildTranscripts(List<String> errors) {
        try {
            TranscriptReindexResult result = transcriptIndexService.reindexAll();
            if (result.failed() > 0) {
                errors.add("transcript: failed=" + result.failed()
                        + ", " + String.join("; ", result.errors()));
            }
            return result;
        } catch (Exception e) {
            errors.add("transcript: " + truncateError(rootMessage(e)));
            return new TranscriptReindexResult(0, 0, List.of());
        }
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
     * 截断错误信息，避免响应体过大。
     */
    private String truncateError(String message) {
        if (message == null) {
            return "unknown";
        }
        return message.length() <= MAX_ERROR_CHARS ? message : message.substring(0, MAX_ERROR_CHARS);
    }

    /**
     * 向量索引重建结果。
     *
     * @param available    向量索引是否可用
     * @param upserted     成功写入的文档数
     * @param staleDeleted 清理的已下线文档数
     * @param error        失败原因，成功时为 null
     */
    private record VectorOutcome(boolean available, int upserted, int staleDeleted, String error) {
    }

    /**
     * BM25 索引重建结果。
     *
     * @param available BM25 索引是否可用
     * @param indexed   成功写入的文档数
     * @param error     失败原因，成功时为 null
     */
    private record Bm25Outcome(boolean available, int indexed, String error) {
    }
}
