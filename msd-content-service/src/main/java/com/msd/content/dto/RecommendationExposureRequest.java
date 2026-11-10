package com.msd.content.dto;

import java.util.List;

/**
 * 推荐曝光上报，字段与 interaction 的 /internal-source/recommendations/exposures 对齐。
 *
 * @param userId       用户 ID
 * @param requestId    推荐请求 ID
 * @param scene        场景
 * @param algorithm    算法版本
 * @param experimentId 实验 ID
 * @param variant      实验分桶
 * @param items        曝光条目
 */
public record RecommendationExposureRequest(
        Long userId,
        String requestId,
        String scene,
        String algorithm,
        String experimentId,
        String variant,
        List<Item> items
) {

    /**
     * 一条曝光。
     *
     * @param dramaId  短剧 ID
     * @param position 展示位置
     * @param reason   推荐理由
     */
    public record Item(Long dramaId, Integer position, String reason) {
    }
}
