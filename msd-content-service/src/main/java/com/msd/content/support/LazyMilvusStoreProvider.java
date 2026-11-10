package com.msd.content.support;

import com.msd.content.config.RagProperties;
import com.msd.content.rag.RagMetadata;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 延迟创建 Milvus 客户端和 VectorStore。
 *
 * <p>MilvusServiceClient 构造时立即建立 gRPC 连接。如果使用官方 starter，
 * Milvus 未启动会导致整个服务启动失败；这里改为首次索引或检索时再连接。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LazyMilvusStoreProvider {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(LazyMilvusStoreProvider.class);

    // embedding 模型，首次初始化时传给 VectorStore
    private final EmbeddingModel embeddingModel;
    // Milvus 配置
    private final RagProperties properties;
    // 初始化互斥锁，保证只建一次连接
    private final ReentrantLock lock = new ReentrantLock();

    // 底层 gRPC 客户端，初始化成功后才非空
    private volatile MilvusServiceClient client;
    // 延迟创建的向量库
    private volatile MilvusVectorStore vectorStore;
    // 初始化失败后的退避截止时间
    private volatile long unavailableUntil;
    // 最近一次初始化失败原因
    private volatile String lastError;

    /**
     * 注入 embedding 模型和 Milvus 配置。
     */
    public LazyMilvusStoreProvider(EmbeddingModel embeddingModel, RagProperties properties) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    /**
     * 获取可用的 VectorStore，首次调用时建立连接并初始化 collection。
     *
     * @throws RagUnavailableException Milvus 不可用或处于失败退避期
     */
    public MilvusVectorStore get() {
        MilvusVectorStore existing = vectorStore;
        if (existing != null) {
            return existing;
        }

        // 1. 失败退避期内直接拒绝，避免每个请求都卡在连接超时
        if (System.currentTimeMillis() < unavailableUntil) {
            throw new RagUnavailableException("Milvus 暂不可用：" + lastError);
        }

        // 2. 只允许一个线程初始化，其余线程等待结果
        lock.lock();
        try {
            existing = vectorStore;
            if (existing != null) {
                return existing;
            }
            if (System.currentTimeMillis() < unavailableUntil) {
                throw new RagUnavailableException("Milvus 暂不可用：" + lastError);
            }
            return initialize();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 执行短剧向量检索，只返回 docType=drama 的文档。
     */
    public List<Document> search(String query, int topK) {
        // 1. 推荐链路必须限制文档类型，避免分集摘要进入推荐结果
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_DRAMA)
                .build();
        return search(query, topK, filter);
    }

    /**
     * 检索当前集之前的分集向量。
     */
    public List<Document> searchEpisodes(String query, Long dramaId,
                                         Integer beforeEpisodeNo, int topK) {
        // 1. 第 1 集或缺少过滤条件时没有前情可检索
        if (dramaId == null || beforeEpisodeNo == null || beforeEpisodeNo <= 1) {
            return List.of();
        }
        // 2. docType、短剧 ID 和集数范围同时约束，防止跨剧或剧透
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression filter = builder.and(
                builder.eq(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_EPISODE),
                builder.and(
                        builder.eq(RagMetadata.DRAMA_ID, dramaId),
                        builder.lt(RagMetadata.EPISODE_NO, beforeEpisodeNo)))
                .build();
        return search(query, topK, filter);
    }

    /**
     * 删除某部短剧的短剧文档和全部分集向量。
     *
     * <p>Milvus 按 metadata 中的 dramaId 过滤删除；供增量索引下架时使用。</p>
     */
    public void deleteByDrama(Long dramaId) {
        // 1. 参数非法时直接跳过
        if (dramaId == null || dramaId <= 0) {
            return;
        }
        // 2. 复用 VectorStore 的过滤删除能力
        MilvusVectorStore store = get();
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(RagMetadata.DRAMA_ID, dramaId)
                .build();
        store.delete(filter);
    }

    /**
     * 删除全部字幕片段向量，供全量重建前清理旧数据。
     */
    public void deleteAllTranscripts() {
        MilvusVectorStore store = get();
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_TRANSCRIPT)
                .build();
        store.delete(filter);
    }

    /**
     * 删除某集全部字幕片段向量。
     */
    public void deleteTranscriptsByEpisode(Long episodeId) {
        if (episodeId == null || episodeId <= 0) {
            return;
        }
        MilvusVectorStore store = get();
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression filter = builder.and(
                builder.eq(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_TRANSCRIPT),
                builder.eq(RagMetadata.EPISODE_ID, episodeId))
                .build();
        store.delete(filter);
    }

    /**
     * 检索字幕片段向量，限定短剧和最大集数。
     *
     * <p>当前集的播放位置过滤在 TranscriptContextService 后置执行。</p>
     */
    public List<Document> searchTranscripts(String query, Long dramaId, Integer maxEpisodeNo,
                                            int topK) {
        if (dramaId == null || maxEpisodeNo == null || maxEpisodeNo <= 0) {
            return List.of();
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression filter = builder.and(
                builder.eq(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_TRANSCRIPT),
                builder.and(
                        builder.eq(RagMetadata.DRAMA_ID, dramaId),
                        builder.lte(RagMetadata.EPISODE_NO, maxEpisodeNo)))
                .build();
        return search(query, topK, filter);
    }

    /**
     * 执行带过滤条件的向量检索。
     */
    private List<Document> search(String query, int topK, Filter.Expression filter) {
        // 1. 首次调用会初始化连接和 collection
        MilvusVectorStore store = get();
        // 2. 不设相似度阈值，交给 RRF 和 Rerank 排序
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1))
                .similarityThresholdAll()
                .filterExpression(filter)
                .build();
        return store.similaritySearch(request);
    }

    /**
     * 检查 collection 是否存在；任何异常都视为不可用。
     */
    public boolean ping() {
        try {
            // 1. 确保连接已初始化
            get();
            MilvusServiceClient current = client;
            if (current == null) {
                return false;
            }
            // 2. 查询 collection 是否存在
            R<Boolean> result = current.hasCollection(HasCollectionParam.newBuilder()
                    .withDatabaseName(properties.getMilvus().getDatabaseName())
                    .withCollectionName(properties.getMilvus().getCollectionName())
                    .build());
            return result.getStatus() != null
                    && result.getStatus() == 0
                    && Boolean.TRUE.equals(result.getData());
        } catch (Exception e) {
            log.debug("milvus ping failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 最近一次初始化失败原因。
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 是否已经成功建立连接。
     */
    public boolean isInitialized() {
        return vectorStore != null;
    }

    /**
     * 建立连接并初始化 schema，失败时记录退避时间。
     */
    private MilvusVectorStore initialize() {
        MilvusServiceClient newClient = null;
        try {
            log.info("initializing milvus store: host={}, port={}, collection={}",
                    properties.getMilvus().getHost(),
                    properties.getMilvus().getPort(),
                    properties.getMilvus().getCollectionName());

            // 1. 连接参数全部使用毫秒级超时，避免默认值导致长时间挂起
            ConnectParam connectParam = ConnectParam.newBuilder()
                    .withHost(properties.getMilvus().getHost())
                    .withPort(properties.getMilvus().getPort())
                    .withDatabaseName(properties.getMilvus().getDatabaseName())
                    .withConnectTimeout(properties.getMilvus().getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                    .withRpcDeadline(properties.getMilvus().getRpcDeadlineMs(), TimeUnit.MILLISECONDS)
                    .build();
            newClient = new MilvusServiceClient(connectParam);

            // 2. 使用固定 collection，autoId=false 依赖 drama-{id} 作为主键
            MilvusVectorStore store = MilvusVectorStore.builder(newClient, embeddingModel)
                    .databaseName(properties.getMilvus().getDatabaseName())
                    .collectionName(properties.getMilvus().getCollectionName())
                    .embeddingDimension(properties.getMilvus().getEmbeddingDimension())
                    .indexType(IndexType.valueOf(properties.getMilvus().getIndexType()))
                    .metricType(MetricType.valueOf(properties.getMilvus().getMetricType()))
                    .autoId(false)
                    .initializeSchema(true)
                    .build();

            // 3. afterPropertiesSet 会创建 collection 和索引，失败时统一进入退避
            store.afterPropertiesSet();

            this.client = newClient;
            this.vectorStore = store;
            this.lastError = null;
            this.unavailableUntil = 0;
            log.info("milvus store initialized: collection={}",
                    properties.getMilvus().getCollectionName());
            return store;
        } catch (Exception e) {
            closeQuietly(newClient);
            this.lastError = rootMessage(e);
            this.unavailableUntil = System.currentTimeMillis()
                    + properties.getMilvus().getFailureBackoffMs();
            log.warn("milvus store initialization failed: {}", this.lastError);
            throw new RagUnavailableException("Milvus 初始化失败：" + this.lastError, e);
        }
    }

    /**
     * 应用关闭时释放 gRPC 连接。
     */
    @PreDestroy
    public void close() {
        lock.lock();
        try {
            closeQuietly(client);
            client = null;
            vectorStore = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭客户端，异常只记录 debug 日志。
     */
    private void closeQuietly(MilvusServiceClient target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (Exception e) {
            log.debug("milvus client close failed: {}", e.getMessage());
        }
    }

    /**
     * 取最内层异常信息，避免日志里只有包装异常。
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
