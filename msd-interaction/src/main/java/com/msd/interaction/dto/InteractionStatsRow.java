package com.msd.interaction.dto;

/**
 * 个人中心四项互动计数的一行聚合结果。
 */
public class InteractionStatsRow {

    // 点赞短剧数
    private Long likedDramaCount;
    // 收藏数
    private Long favoriteCount;
    // 评论数
    private Long commentCount;
    // 观看历史条数
    private Long historyCount;

    public Long getLikedDramaCount() {
        return likedDramaCount;
    }

    public void setLikedDramaCount(Long likedDramaCount) {
        this.likedDramaCount = likedDramaCount;
    }

    public Long getFavoriteCount() {
        return favoriteCount;
    }

    public void setFavoriteCount(Long favoriteCount) {
        this.favoriteCount = favoriteCount;
    }

    public Long getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Long commentCount) {
        this.commentCount = commentCount;
    }

    public Long getHistoryCount() {
        return historyCount;
    }

    public void setHistoryCount(Long historyCount) {
        this.historyCount = historyCount;
    }
}
