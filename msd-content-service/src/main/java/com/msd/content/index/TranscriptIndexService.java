package com.msd.content.index;

import com.msd.common.dto.EpisodeTranscriptDTO;
import com.msd.common.event.DramaChangedAction;
import com.msd.common.event.TranscriptChangedEvent;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.RagProperties;
import com.msd.content.dto.TranscriptReindexResult;
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
 * 分集字幕索引：单集 upsert 或删除。
 *
 * <p>字幕是剧情问答的细粒度证据；索引前只导出在线剧集，不把下架内容留在 transcript 索引。</p>
 */
@Service
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TranscriptIndexService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(TranscriptIndexService.class);
    /** 错误信息最大字符数。 */
    private static final int MAX_ERROR_CHARS = 300;

    // 短剧目录导出客户端
    private final DramaCatalogClient catalogClient;
    // 字幕文档构建器
    private final TranscriptDocumentBuilder documentBuilder;
    // 向量库延迟提供者
    private final ObjectProvider<LazyMilvusStoreProvider> milvusProvider;
    // BM25 存储
    private final ObjectProvider<Bm25IndexStore> bm25StoreProvider;
    // RAG 配置
    private final RagProperties properties;
    // Micrometer 注册表，测试可传 null
    private final MeterRegistry meterRegistry;

    /**
     * 注入导出客户端、文档构建器、索引存储、配置和指标。
     */
    public TranscriptIndexService(DramaCatalogClient catalogClient,
                                  TranscriptDocumentBuilder documentBuilder,
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
     * 处理一条字幕变更事件。
     */
    public void apply(TranscriptChangedEvent event) {
        long startedAt = System.currentTimeMillis();
        try {
            if (event == null || event.episodeId() == null || event.episodeId() <= 0) {
                throw new RagIndexException("字幕变更事件缺少 episodeId");
            }
            if (DramaChangedAction.DELETE == event.action()) {
                delete(event.episodeId());
            } else {
                upsert(event.episodeId());
            }
            recordSuccess(event, startedAt);
        } catch (RuntimeException e) {
            recordFailure(event);
            throw e;
        }
    }

    /**
     * 全量重建字幕索引，供短剧全量 reindex 兜底。
     *
     * <p>逐集调用 UPSERT；单集失败只记录，不中断整个重建。</p>
     */
    public TranscriptReindexResult reindexAll() {
        // 1. 先取全量剧集 ID；列表失败时不动现有索引
        List<Long> episodeIds = fetchTranscriptEpisodeIds();
        List<String> errors = new ArrayList<>();
        int episodes = 0;
        int failed = 0;

        // 2. 清理 Milvus 旧字幕向量，避免已删字幕残留；ES 已由全量重建清空
        LazyMilvusStoreProvider provider = milvusProvider.getIfAvailable();
        if (properties.getVector().isEnabled() && provider != null) {
            try {
                provider.deleteAllTranscripts();
            } catch (Exception e) {
                errors.add("vector: " + truncateError(rootMessage(e)));
            }
        }

        // 3. 逐集 UPSERT；单集失败只记录，不中断整个重建
        for (Long episodeId : episodeIds) {
            episodes += 1;
            try {
                apply(new TranscriptChangedEvent("reindex-" + episodeId, episodeId,
                        DramaChangedAction.UPSERT, System.currentTimeMillis()));
            } catch (Exception e) {
                failed += 1;
                if (errors.size() < 20) {
                    errors.add("episode " + episodeId + ": " + truncateError(rootMessage(e)));
                }
            }
        }
        log.info("transcript reindex finished: episodes={}, failed={}", episodes, failed);
        return new TranscriptReindexResult(episodes, failed, List.copyOf(errors));
    }

    /**
     * 分页拉取有字幕的剧集 ID。
     */
    private List<Long> fetchTranscriptEpisodeIds() {
        // drama 的 PageQuery 上限是 100，这里必须与接口一致，避免第一页就提前结束
        int pageSize = Math.min(Math.max(properties.getCatalog().getPageSize(), 1), 100);
        int maxPages = Math.max(properties.getCatalog().getMaxPages(), 1);
        List<Long> ids = new ArrayList<>();
        for (int page = 1; page <= maxPages; page += 1) {
            Result<PageResult<Long>> response;
            try {
                response = catalogClient.transcriptEpisodes(page, pageSize);
            } catch (Exception e) {
                throw new RagIndexException("字幕剧集列表调用失败：" + rootMessage(e), e);
            }
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new RagIndexException("字幕剧集列表导出失败："
                        + (response == null ? "无响应" : response.getMessage()));
            }
            List<Long> records = response.getData().getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            for (Long episodeId : records) {
                if (episodeId != null && episodeId > 0) {
                    ids.add(episodeId);
                }
            }
            if (records.size() < pageSize) {
                break;
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 拉取字幕并重建单集索引；无字幕时转为删除。
     */
    private void upsert(Long episodeId) {
        EpisodeTranscriptDTO transcript = fetch(episodeId);
        if (transcript == null || transcript.segments().isEmpty()) {
            log.info("transcript index empty, fallback to delete: episodeId={}", episodeId);
            delete(episodeId);
            return;
        }
        List<Document> documents = transcript.segments().stream()
                .map(segment -> documentBuilder.build(transcript, segment))
                .toList();
        List<String> errors = new ArrayList<>();
        boolean vectorUpdated = upsertVector(episodeId, documents, errors);
        boolean bm25Updated = upsertBm25(episodeId, documents, errors);
        if (!errors.isEmpty()) {
            throw new RagIndexException("字幕索引部分失败：episodeId=" + episodeId
                    + ", errors=" + String.join("; ", errors));
        }
        log.info("transcript index upsert: episodeId={}, segments={}, vector={}, bm25={}",
                episodeId, documents.size(), vectorUpdated, bm25Updated);
    }

    /**
     * 拉取单集字幕；下游失败抛出触发 Kafka 重试。
     */
    private EpisodeTranscriptDTO fetch(Long episodeId) {
        try {
            Result<EpisodeTranscriptDTO> response = catalogClient.transcript(episodeId);
            if (response == null || !response.isSuccess()) {
                String message = response == null ? "无响应" : response.getMessage();
                throw new RagIndexException("字幕导出失败：" + message);
            }
            return response.getData();
        } catch (RagIndexException e) {
            throw e;
        } catch (Exception e) {
            throw new RagIndexException("字幕导出调用失败：" + rootMessage(e), e);
        }
    }

    /**
     * 删除单集两路字幕索引。
     */
    private void delete(Long episodeId) {
        List<String> errors = new ArrayList<>();
        deleteVector(episodeId, errors);
        deleteBm25(episodeId, errors);
        if (!errors.isEmpty()) {
            throw new RagIndexException("字幕索引删除失败：episodeId=" + episodeId
                    + ", errors=" + String.join("; ", errors));
        }
        log.info("transcript index deleted: episodeId={}", episodeId);
    }

    /**
     * 先删后加分批写入 Milvus。
     */
    private boolean upsertVector(Long episodeId, List<Document> documents,
                                 List<String> errors) {
        if (!properties.getVector().isEnabled()) {
            return true;
        }
        LazyMilvusStoreProvider provider = milvusProvider.getIfAvailable();
        if (provider == null) {
            errors.add("vector: Milvus 提供者不存在");
            return false;
        }
        try {
            provider.deleteTranscriptsByEpisode(episodeId);
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
     * 先删后写 ES 并刷新。
     */
    private boolean upsertBm25(Long episodeId, List<Document> documents, List<String> errors) {
        if (!properties.getBm25().isEnabled()) {
            return true;
        }
        Bm25IndexStore store = bm25StoreProvider.getIfAvailable();
        if (store == null) {
            errors.add("bm25: BM25 索引实现不存在");
            return false;
        }
        try {
            // 索引不存在时不能直接写入，避免 ES 用动态 mapping 自动建索引
            if (!store.ping()) {
                errors.add("bm25: 索引不可用，请先执行全量 reindex");
                return false;
            }
            store.deleteTranscriptsByEpisode(episodeId);
            store.upsertTranscripts(documents);
            store.refresh();
            return true;
        } catch (Exception e) {
            errors.add("bm25: " + truncateError(rootMessage(e)));
            return false;
        }
    }

    /**
     * 删除向量索引。
     */
    private void deleteVector(Long episodeId, List<String> errors) {
        if (!properties.getVector().isEnabled()) {
            return;
        }
        LazyMilvusStoreProvider provider = milvusProvider.getIfAvailable();
        if (provider == null) {
            errors.add("vector: Milvus 提供者不存在");
            return;
        }
        try {
            provider.deleteTranscriptsByEpisode(episodeId);
        } catch (Exception e) {
            errors.add("vector: " + truncateError(rootMessage(e)));
        }
    }

    /**
     * 删除 BM25 索引。
     */
    private void deleteBm25(Long episodeId, List<String> errors) {
        if (!properties.getBm25().isEnabled()) {
            return;
        }
        Bm25IndexStore store = bm25StoreProvider.getIfAvailable();
        if (store == null) {
            errors.add("bm25: BM25 索引实现不存在");
            return;
        }
        try {
            store.deleteTranscriptsByEpisode(episodeId);
        } catch (Exception e) {
            errors.add("bm25: " + truncateError(rootMessage(e)));
        }
    }

    /**
     * 记录成功计数和索引延迟。
     */
    private void recordSuccess(TranscriptChangedEvent event, long startedAt) {
        if (meterRegistry == null || event == null) {
            return;
        }
        String action = event.action() == null ? "UNKNOWN" : event.action().name();
        meterRegistry.counter("rag_transcript_index_upsert_total", "action", action)
                .increment();
        long lagMs = event.occurredAt() > 0
                ? Math.max(System.currentTimeMillis() - event.occurredAt(), 0) : 0;
        meterRegistry.timer("rag_transcript_index_lag", "action", action)
                .record(lagMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录失败计数。
     */
    private void recordFailure(TranscriptChangedEvent event) {
        if (meterRegistry == null || event == null) {
            return;
        }
        String action = event.action() == null ? "UNKNOWN" : event.action().name();
        meterRegistry.counter("rag_transcript_index_failure_total", "action", action)
                .increment();
    }

    /**
     * 截断错误信息。
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
