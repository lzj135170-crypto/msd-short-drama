package com.msd.interaction.dto;

import java.time.LocalDateTime;

/**
 * 用户行为聚合行，Mapper 按短剧去重后返回。
 */
public class UserBehaviorRow {

    // 短剧 ID
    private Long dramaId;
    // 收藏/点赞/观看中的最大权重
    private Double weight;
    // 最近一次行为时间
    private LocalDateTime lastTime;

    /**
     * 供 MyBatis 反射创建实例。
     */
    public UserBehaviorRow() {
    }

    /**
     * 全参构造，便于测试直接创建。
     */
    public UserBehaviorRow(Long dramaId, Double weight, LocalDateTime lastTime) {
        this.dramaId = dramaId;
        this.weight = weight;
        this.lastTime = lastTime;
    }

    /** 短剧 ID。 */
    public Long getDramaId() {
        return dramaId;
    }

    /** 设置短剧 ID。 */
    public void setDramaId(Long dramaId) {
        this.dramaId = dramaId;
    }

    /** 聚合权重。 */
    public Double getWeight() {
        return weight;
    }

    /** 设置聚合权重。 */
    public void setWeight(Double weight) {
        this.weight = weight;
    }

    /** 最近行为时间。 */
    public LocalDateTime getLastTime() {
        return lastTime;
    }

    /** 设置最近行为时间。 */
    public void setLastTime(LocalDateTime lastTime) {
        this.lastTime = lastTime;
    }
}
