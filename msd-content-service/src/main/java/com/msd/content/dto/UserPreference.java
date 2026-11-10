package com.msd.content.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户偏好画像：由 interaction 行为和 drama 目录元数据合成。
 *
 * @param categories       Top 分类权重
 * @param tags             Top 标签权重
 * @param recentDramaIds   最近互动的短剧 ID
 * @param favoriteDramaIds   收藏的短剧 ID
 * @param continueDramaIds   最近观看的短剧 ID
 * @param coOccurrenceDramas 共同观看召回及得分
 * @param dislikedDramaIds   用户标记“不感兴趣”的短剧 ID
 * @param empty              是否没有可用偏好
 * @param degraded           画像是否因依赖失败而降级
 */
public record UserPreference(
        List<CategoryWeight> categories,
        List<TagWeight> tags,
        List<Long> recentDramaIds,
        List<Long> favoriteDramaIds,
        List<Long> continueDramaIds,
        List<DramaWeight> coOccurrenceDramas,
        List<Long> dislikedDramaIds,
        boolean empty,
        boolean degraded
) {

    /**
     * 反序列化时把 null 归一为空列表。
     */
    public UserPreference {
        categories = categories == null ? List.of() : List.copyOf(categories);
        tags = tags == null ? List.of() : List.copyOf(tags);
        recentDramaIds = recentDramaIds == null ? List.of() : List.copyOf(recentDramaIds);
        favoriteDramaIds = favoriteDramaIds == null ? List.of() : List.copyOf(favoriteDramaIds);
        continueDramaIds = continueDramaIds == null ? List.of() : List.copyOf(continueDramaIds);
        coOccurrenceDramas = coOccurrenceDramas == null
                ? List.of() : List.copyOf(coOccurrenceDramas);
        dislikedDramaIds = dislikedDramaIds == null ? List.of() : List.copyOf(dislikedDramaIds);
    }

    /**
     * 兼容旧调用方，推荐归因字段为空。
     */
    public UserPreference(List<CategoryWeight> categories,
                          List<TagWeight> tags,
                          List<Long> recentDramaIds,
                          List<Long> favoriteDramaIds,
                          boolean empty,
                          boolean degraded) {
        this(categories, tags, recentDramaIds, favoriteDramaIds,
                List.of(), List.of(), List.of(), empty, degraded);
    }

    /**
     * 空画像。
     */
    public static UserPreference empty(boolean degraded) {
        return new UserPreference(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), true, degraded);
    }

    /**
     * 共同观看召回得分。
     *
     * @param dramaId 短剧 ID
     * @param score   共现得分
     */
    public record DramaWeight(Long dramaId, double score) {
    }

    /**
     * 生成偏好检索词，供多查询扩展使用。
     */
    public String preferenceQuery() {
        // 1. 分类名和标签名去重，保持权重顺序
        Set<String> terms = new LinkedHashSet<>();
        categories.forEach(item -> addTerm(terms, item.name()));
        tags.forEach(item -> addTerm(terms, item.name()));
        // 2. 最多 8 个词，避免偏好 query 过长
        return terms.stream().limit(8).reduce((left, right) -> left + " " + right).orElse("");
    }

    /**
     * 追加非空词。
     */
    private void addTerm(Set<String> terms, String term) {
        if (term != null && !term.isBlank()) {
            terms.add(term.trim());
        }
    }

    /**
     * 分类权重。
     *
     * @param id    分类 ID
     * @param name  分类名
     * @param score 偏好得分
     */
    public record CategoryWeight(Long id, String name, double score) {
    }

    /**
     * 标签权重。
     *
     * @param name  标签名
     * @param score 偏好得分
     */
    public record TagWeight(String name, double score) {
    }
}
