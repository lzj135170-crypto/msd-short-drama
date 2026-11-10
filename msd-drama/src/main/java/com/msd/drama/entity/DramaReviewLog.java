package com.msd.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 短剧审核操作日志，对应 t_drama_review_log。
 */
@TableName("t_drama_review_log")
public class DramaReviewLog {

    // 日志 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 短剧 ID
    private Long dramaId;
    // 操作类型
    private String action;
    // 变更前状态
    private Integer fromStatus;
    // 变更后状态
    private Integer toStatus;
    // 操作人，AI 为 0
    private Long operatorId;
    // 操作来源：HUMAN/AI
    private String source;
    // 审核意见
    private String remark;
    // 创建时间
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDramaId() {
        return dramaId;
    }

    public void setDramaId(Long dramaId) {
        this.dramaId = dramaId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(Integer fromStatus) {
        this.fromStatus = fromStatus;
    }

    public Integer getToStatus() {
        return toStatus;
    }

    public void setToStatus(Integer toStatus) {
        this.toStatus = toStatus;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
