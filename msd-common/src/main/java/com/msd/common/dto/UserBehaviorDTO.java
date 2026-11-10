package com.msd.common.dto;

import java.util.List;

/**
 * 用户短剧行为聚合结果，供推荐服务合成画像。
 *
 * <p>只包含行为权重和短剧 ID，不包含分类、标签等 drama 元数据。</p>
 *
 * @param userId             用户 ID
 * @param weights            按短剧聚合的权重，已去重
 * @param recentDramaIds     最近互动的短剧 ID，按时间倒序
 * @param favoriteDramaIds   收藏的短剧 ID，按收藏时间倒序
 * @param continueDramaIds   最近观看的短剧 ID，按观看时间倒序
 * @param coOccurrenceDramas 共同观看召回，按共现得分倒序
 * @param dislikedDramaIds   用户标记“不感兴趣”的短剧 ID
 */
public record UserBehaviorDTO(
        Long userId,
        List<DramaWeight> weights,
        List<Long> recentDramaIds,
        List<Long> favoriteDramaIds,
        List<Long> continueDramaIds,
        List<DramaWeight> coOccurrenceDramas,
        List<Long> dislikedDramaIds
) {

    /**
     * 单部短剧的行为权重或召回得分。
     *
     * @param dramaId 短剧 ID
     * @param weight  收藏/点赞/观看聚合后的最大权重，或共现得分
     */
    public record DramaWeight(Long dramaId, double weight) {
    }

    /**
     * 反序列化时把 null 归一为空列表。
     */
    public UserBehaviorDTO {
        weights = weights == null ? List.of() : List.copyOf(weights);
        recentDramaIds = recentDramaIds == null ? List.of() : List.copyOf(recentDramaIds);
        favoriteDramaIds = favoriteDramaIds == null ? List.of() : List.copyOf(favoriteDramaIds);
        continueDramaIds = continueDramaIds == null ? List.of() : List.copyOf(continueDramaIds);
        coOccurrenceDramas = coOccurrenceDramas == null
                ? List.of() : List.copyOf(coOccurrenceDramas);
        dislikedDramaIds = dislikedDramaIds == null ? List.of() : List.copyOf(dislikedDramaIds);
    }

    /**
     * 兼容 P0/P1 旧调用方，推荐归因字段为空。
     */
    public UserBehaviorDTO(Long userId, List<DramaWeight> weights, List<Long> recentDramaIds,
                           List<Long> favoriteDramaIds) {
        this(userId, weights, recentDramaIds, favoriteDramaIds,
                List.of(), List.of(), List.of());
    }

    /**
     * 空行为，避免调用方处理 null。
     */
    public static UserBehaviorDTO empty(Long userId) {
        return new UserBehaviorDTO(userId, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }
}
