package com.msd.content.dto;

import java.util.List;
import java.util.UUID;

/**
 * 首页「为你推荐」结果。
 *
 * @param items        推荐卡片
 * @param personalized 是否命中用户偏好；false 表示回退热播
 * @param coldStart    是否冷启动
 * @param degraded     是否因偏好/目录依赖失败而降级
 * @param requestId    本次推荐请求 ID，用于曝光/点击归因
 * @param algorithm    算法版本
 * @param experimentId 实验 ID
 * @param variant      实验分桶
 */
public record PersonalizedFeed(
        List<PersonalizedDramaCard> items,
        boolean personalized,
        boolean coldStart,
        boolean degraded,
        String requestId,
        String algorithm,
        String experimentId,
        String variant
) {

    /**
     * 反序列化时保证 items 不为 null，并补齐归因字段。
     */
    public PersonalizedFeed {
        items = items == null ? List.of() : List.copyOf(items);
        requestId = requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId;
        algorithm = algorithm == null ? "" : algorithm;
        experimentId = experimentId == null ? "" : experimentId;
        variant = variant == null ? "" : variant;
    }

    /**
     * 兼容旧调用方，默认使用 v2 算法和随机请求 ID。
     */
    public PersonalizedFeed(List<PersonalizedDramaCard> items, boolean personalized,
                            boolean coldStart, boolean degraded) {
        this(items, personalized, coldStart, degraded, null, "v2", "", "");
    }

    /**
     * 空结果。
     */
    public static PersonalizedFeed empty(boolean degraded) {
        return new PersonalizedFeed(List.of(), false, false, degraded, null, "v2", "", "");
    }
}
