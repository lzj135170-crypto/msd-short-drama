package com.msd.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 分集字幕元数据，对应 t_episode_subtitle。
 */
@TableName("t_episode_subtitle")
public class EpisodeSubtitle {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 剧集 ID
    private Long episodeId;
    // 语言
    private String language;
    // 格式：SRT/VTT/ASR
    private String format;
    // 原始字幕对象键或内容哈希
    private String objectKey;
    // 来源：UPLOAD/ASR
    private String source;
    // 状态：0 隐藏，1 可用
    private Integer status;
    // 片段数
    private Integer segmentCount;

    /** 获取主键 ID。 */
    public Long getId() {
        return id;
    }

    /** 设置主键 ID。 */
    public void setId(Long id) {
        this.id = id;
    }

    /** 获取剧集 ID。 */
    public Long getEpisodeId() {
        return episodeId;
    }

    /** 设置剧集 ID。 */
    public void setEpisodeId(Long episodeId) {
        this.episodeId = episodeId;
    }

    /** 获取语言。 */
    public String getLanguage() {
        return language;
    }

    /** 设置语言。 */
    public void setLanguage(String language) {
        this.language = language;
    }

    /** 获取格式。 */
    public String getFormat() {
        return format;
    }

    /** 设置格式。 */
    public void setFormat(String format) {
        this.format = format;
    }

    /** 获取对象键。 */
    public String getObjectKey() {
        return objectKey;
    }

    /** 设置对象键。 */
    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    /** 获取来源。 */
    public String getSource() {
        return source;
    }

    /** 设置来源。 */
    public void setSource(String source) {
        this.source = source;
    }

    /** 获取状态。 */
    public Integer getStatus() {
        return status;
    }

    /** 设置状态。 */
    public void setStatus(Integer status) {
        this.status = status;
    }

    /** 获取片段数。 */
    public Integer getSegmentCount() {
        return segmentCount;
    }

    /** 设置片段数。 */
    public void setSegmentCount(Integer segmentCount) {
        this.segmentCount = segmentCount;
    }
}
