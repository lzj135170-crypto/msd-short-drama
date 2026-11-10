package com.msd.content.dto;

import java.util.List;

/**
 * 单次增量索引结果。
 *
 * @param dramaId      短剧 ID
 * @param deleted      是否执行了删除
 * @param episodeCount 写入或删除涉及的分集数
 * @param vectorUpdated 向量索引是否更新成功
 * @param bm25Updated  BM25 索引是否更新成功
 * @param errors       索引错误
 * @param elapsedMs    耗时（毫秒）
 */
public record IndexUpdateResult(
        Long dramaId,
        boolean deleted,
        int episodeCount,
        boolean vectorUpdated,
        boolean bm25Updated,
        List<String> errors,
        long elapsedMs
) {

    /**
     * 统一空值，避免调用方判空。
     */
    public IndexUpdateResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * 是否有任一路索引失败。
     */
    public boolean partial() {
        return !errors.isEmpty();
    }
}
