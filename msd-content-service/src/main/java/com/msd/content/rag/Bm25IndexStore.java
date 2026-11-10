package com.msd.content.rag;

import com.msd.common.dto.DramaRagExportDTO;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * BM25 索引存储抽象。
 *
 * <p>4.6 由 ElasticsearchBm25Store 实现；在此之前索引服务可以正常构建向量部分，
 * BM25 分支返回不可用并标记 partial。</p>
 */
public interface Bm25IndexStore {

    /**
     * 删除并重建索引结构。
     */
    void recreateIndex();

    /**
     * 全量写入短剧文档和分集文档，返回成功条数。
     */
    int indexAll(List<DramaRagExportDTO> dramas);

    /**
     * 更新单条分集文档，供摘要回写后即时生效。
     */
    void upsertEpisode(Document document);

    /**
     * 删除某部短剧的短剧文档和全部分集文档，供增量索引下架时使用。
     *
     * @return 删除的文档数；索引不存在时返回 0
     */
    int deleteByDrama(Long dramaId);

    /**
     * 删除某集的全部字幕片段文档。
     *
     * @return 删除的文档数；索引不存在时返回 0
     */
    int deleteTranscriptsByEpisode(Long episodeId);

    /**
     * 写入单集字幕片段，返回成功条数。
     */
    int upsertTranscripts(List<Document> documents);

    /**
     * 检索字幕片段，限定短剧和最大集数；当前集时间范围由调用方后置过滤。
     */
    List<Document> searchTranscripts(String queryText, Long dramaId, Integer maxEpisodeNo,
                                     int topK);

    /**
     * 刷新索引，保证写入立即可查。
     */
    void refresh();

    /**
     * 检查索引是否可用。
     */
    boolean ping();

    /**
     * 最近一次失败原因。
     */
    String getLastError();
}
