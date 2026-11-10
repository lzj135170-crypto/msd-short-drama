package com.msd.content.index;

import com.msd.content.config.RagProperties;
import com.msd.content.rag.Bm25IndexStore;
import com.msd.content.support.LazyMilvusStoreProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 单集索引更新，摘要回写后立即刷新向量和 BM25 文档。
 *
 * <p>索引失败只标记降级，不影响摘要已经落库。</p>
 */
@Service
public class EpisodeIndexService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(EpisodeIndexService.class);

    // RAG 配置
    private final RagProperties properties;
    // 文档构建器，RAG 关闭时可能不存在
    private final ObjectProvider<DramaDocumentBuilder> documentBuilderProvider;
    // 向量库延迟提供者，RAG 关闭时可能不存在
    private final ObjectProvider<LazyMilvusStoreProvider> milvusProvider;
    // BM25 存储，实现缺失时跳过
    private final ObjectProvider<Bm25IndexStore> bm25StoreProvider;

    /**
     * 注入配置和三个索引依赖提供者。
     */
    public EpisodeIndexService(RagProperties properties,
                               ObjectProvider<DramaDocumentBuilder> documentBuilderProvider,
                               ObjectProvider<LazyMilvusStoreProvider> milvusProvider,
                               ObjectProvider<Bm25IndexStore> bm25StoreProvider) {
        this.properties = properties;
        this.documentBuilderProvider = documentBuilderProvider;
        this.milvusProvider = milvusProvider;
        this.bm25StoreProvider = bm25StoreProvider;
    }

    /**
     * 更新单集文档；两条索引都成功或都关闭时返回 true。
     */
    public boolean upsert(Long episodeId, Long dramaId, String dramaTitle,
                          Integer episodeNo, String episodeTitle, String summary) {
        return upsert(episodeId, dramaId, dramaTitle, episodeNo, episodeTitle, summary, 0);
    }

    /**
     * 更新单集文档，并保留价格 metadata 供前情权限过滤。
     */
    public boolean upsert(Long episodeId, Long dramaId, String dramaTitle,
                          Integer episodeNo, String episodeTitle, String summary,
                          Integer pricePoints) {
        // 1. RAG 关闭或文档构建器缺失时视为无需更新
        if (!properties.isEnabled() || episodeId == null) {
            return true;
        }
        DramaDocumentBuilder builder = documentBuilderProvider.getIfAvailable();
        if (builder == null) {
            return true;
        }

        // 2. 构建和全量重建一致的 episode-{id} 文档
        Document document = builder.buildEpisode(
                dramaId, dramaTitle, episodeId, episodeNo, episodeTitle, summary, pricePoints);

        // 3. 两路索引互不影响，任一失败只返回 false
        boolean vectorOk = upsertVector(document);
        boolean bm25Ok = upsertBm25(document);
        return vectorOk && bm25Ok;
    }

    /**
     * 更新 Milvus 单条向量；先删后插，避免主键冲突。
     */
    private boolean upsertVector(Document document) {
        if (!properties.getVector().isEnabled()) {
            return true;
        }
        LazyMilvusStoreProvider provider = milvusProvider.getIfAvailable();
        if (provider == null) {
            return false;
        }
        try {
            MilvusVectorStore store = provider.get();
            store.delete(List.of(document.getId()));
            store.add(List.of(document));
            return true;
        } catch (Exception e) {
            log.warn("episode vector upsert failed: episodeId={}, error={}",
                    document.getId(), rootMessage(e));
            return false;
        }
    }

    /**
     * 更新 Elasticsearch 单条分集文档。
     */
    private boolean upsertBm25(Document document) {
        if (!properties.getBm25().isEnabled()) {
            return true;
        }
        Bm25IndexStore store = bm25StoreProvider.getIfAvailable();
        if (store == null) {
            return false;
        }
        try {
            store.upsertEpisode(document);
            return true;
        } catch (Exception e) {
            log.warn("episode bm25 upsert failed: episodeId={}, error={}",
                    document.getId(), rootMessage(e));
            return false;
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
}
