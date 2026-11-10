package com.msd.content.service;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 短剧目录短期缓存，供个性化推荐和画像合成复用。
 *
 * <p>目录数据来自 drama 的 RAG 导出接口，60 秒刷新一次；失败时保留上一份缓存。</p>
 */
@Service
public class DramaCatalogCacheService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(DramaCatalogCacheService.class);
    /** 缓存有效期（毫秒）。 */
    private static final long CACHE_TTL_MS = 60_000L;

    // 短剧目录客户端
    private final DramaCatalogClient catalogClient;
    // RAG 配置中的目录分页参数
    private final RagProperties properties;

    // 当前目录快照
    private volatile List<DramaRagExportDTO> cached = List.of();
    // 最近一次加载时间
    private volatile long loadedAt;

    /**
     * 注入目录客户端和配置。
     */
    public DramaCatalogCacheService(DramaCatalogClient catalogClient, RagProperties properties) {
        this.catalogClient = catalogClient;
        this.properties = properties;
    }

    /**
     * 返回目录快照；过期时同步刷新，失败时返回上一份缓存。
     */
    public List<DramaRagExportDTO> list() {
        // 1. 未过期直接返回
        long now = System.currentTimeMillis();
        if (now - loadedAt < CACHE_TTL_MS) {
            return cached;
        }

        synchronized (this) {
            // 2. 双重检查，避免并发刷新
            now = System.currentTimeMillis();
            if (now - loadedAt < CACHE_TTL_MS) {
                return cached;
            }

            // 3. 分页拉取已上架短剧；失败时保留旧快照
            try {
                cached = fetchAll();
                log.info("drama catalog refreshed: size={}", cached.size());
            } catch (Exception ex) {
                log.warn("drama catalog refresh failed, keep previous snapshot", ex);
            } finally {
                // 4. 失败也更新时间，避免每次请求都打 drama
                loadedAt = now;
            }
            return cached;
        }
    }

    /**
     * 按配置分页拉取完整目录。
     */
    private List<DramaRagExportDTO> fetchAll() {
        int pageSize = Math.max(properties.getCatalog().getPageSize(), 1);
        int maxPages = Math.max(properties.getCatalog().getMaxPages(), 1);
        List<DramaRagExportDTO> result = new ArrayList<>();

        // 1. 逐页拉取，空页或达到上限即停止
        for (int page = 1; page <= maxPages; page++) {
            Result<PageResult<DramaRagExportDTO>> response = catalogClient.export(page, pageSize);
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new IllegalStateException("短剧目录导出失败");
            }
            PageResult<DramaRagExportDTO> data = response.getData();
            List<DramaRagExportDTO> records = data.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            result.addAll(records);
            if (result.size() >= data.getTotal()) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
