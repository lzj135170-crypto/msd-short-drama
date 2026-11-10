package com.msd.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 推荐曝光记录，对应 t_recommendation_exposure。
 */
@TableName("t_recommendation_exposure")
public class RecommendationExposure {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;
    // 推荐请求 ID
    private String requestId;
    // 用户 ID
    private Long userId;
    // 场景
    private String scene;
    // 算法版本
    private String algorithm;
    // 实验 ID
    private String experimentId;
    // 实验分桶
    private String variant;
    // 曝光短剧 ID
    private Long dramaId;
    // 展示位置
    private Integer position;
    // 推荐理由
    private String reason;
    // 曝光时间
    private LocalDateTime createTime;

    /** 主键。 */
    public Long getId() {
        return id;
    }

    /** 设置主键。 */
    public void setId(Long id) {
        this.id = id;
    }

    /** 推荐请求 ID。 */
    public String getRequestId() {
        return requestId;
    }

    /** 设置推荐请求 ID。 */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /** 用户 ID。 */
    public Long getUserId() {
        return userId;
    }

    /** 设置用户 ID。 */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** 场景。 */
    public String getScene() {
        return scene;
    }

    /** 设置场景。 */
    public void setScene(String scene) {
        this.scene = scene;
    }

    /** 算法版本。 */
    public String getAlgorithm() {
        return algorithm;
    }

    /** 设置算法版本。 */
    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    /** 实验 ID。 */
    public String getExperimentId() {
        return experimentId;
    }

    /** 设置实验 ID。 */
    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    /** 实验分桶。 */
    public String getVariant() {
        return variant;
    }

    /** 设置实验分桶。 */
    public void setVariant(String variant) {
        this.variant = variant;
    }

    /** 曝光短剧 ID。 */
    public Long getDramaId() {
        return dramaId;
    }

    /** 设置曝光短剧 ID。 */
    public void setDramaId(Long dramaId) {
        this.dramaId = dramaId;
    }

    /** 展示位置。 */
    public Integer getPosition() {
        return position;
    }

    /** 设置展示位置。 */
    public void setPosition(Integer position) {
        this.position = position;
    }

    /** 推荐理由。 */
    public String getReason() {
        return reason;
    }

    /** 设置推荐理由。 */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /** 曝光时间。 */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /** 设置曝光时间。 */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
