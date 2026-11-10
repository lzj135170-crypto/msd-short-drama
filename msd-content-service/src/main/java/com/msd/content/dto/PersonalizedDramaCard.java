package com.msd.content.dto;

import java.util.List;

/**
 * 首页个性化推荐卡片，字段与前端 DramaCard 对齐。
 *
 * @param dramaId      短剧 ID
 * @param title        标题
 * @param subtitle     副标题
 * @param coverUrl     封面地址
 * @param categoryName 分类名
 * @param episodeCount 总集数
 * @param viewCount    播放量
 * @param likeCount    点赞量
 * @param reason       推荐理由
 * @param matchedTags  命中的用户偏好标签
 */
public record PersonalizedDramaCard(
        Long dramaId,
        String title,
        String subtitle,
        String coverUrl,
        String categoryName,
        Integer episodeCount,
        Long viewCount,
        Long likeCount,
        String reason,
        List<String> matchedTags
) {

    /**
     * 反序列化旧缓存时补齐推荐理由和标签。
     */
    public PersonalizedDramaCard {
        reason = reason == null ? "" : reason;
        matchedTags = matchedTags == null ? List.of() : List.copyOf(matchedTags);
    }

    /**
     * 兼容旧调用方，默认没有推荐理由。
     */
    public PersonalizedDramaCard(Long dramaId,
                                 String title,
                                 String subtitle,
                                 String coverUrl,
                                 String categoryName,
                                 Integer episodeCount,
                                 Long viewCount,
                                 Long likeCount) {
        this(dramaId, title, subtitle, coverUrl, categoryName, episodeCount,
                viewCount, likeCount, "", List.of());
    }
}
