package com.msd.content.dto;

import java.util.List;

/**
 * 相似短剧卡片，字段与前端 DramaCard 对齐。
 *
 * @param dramaId      短剧 ID
 * @param title        标题
 * @param subtitle     副标题
 * @param coverUrl     封面地址
 * @param categoryName 分类名
 * @param episodeCount 总集数
 * @param viewCount    播放量
 * @param likeCount    点赞量
 * @param score        相似度或规则得分
 * @param reason       推荐理由
 * @param matchedTags  共同标签
 */
public record RelatedDramaCard(
        Long dramaId,
        String title,
        String subtitle,
        String coverUrl,
        String categoryName,
        Integer episodeCount,
        Long viewCount,
        Long likeCount,
        Double score,
        String reason,
        List<String> matchedTags
) {

    /**
     * 统一空值，避免前端判空。
     */
    public RelatedDramaCard {
        reason = reason == null ? "" : reason;
        matchedTags = matchedTags == null ? List.of() : List.copyOf(matchedTags);
    }
}
