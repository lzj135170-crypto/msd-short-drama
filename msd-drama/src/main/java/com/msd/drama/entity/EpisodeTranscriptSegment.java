package com.msd.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 分集字幕片段，对应 t_episode_transcript_segment。
 */
@TableName("t_episode_transcript_segment")
public class EpisodeTranscriptSegment {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 字幕 ID
    private Long subtitleId;
    // 剧集 ID
    private Long episodeId;
    // 短剧 ID
    private Long dramaId;
    // 集数序号
    private Integer episodeNo;
    // 开始时间（毫秒）
    private Integer startMs;
    // 结束时间（毫秒）
    private Integer endMs;
    // 字幕文本
    private String text;
    // 状态：0 隐藏，1 可用
    private Integer status;

    /** 获取主键 ID。 */
    public Long getId() {
        return id;
    }

    /** 设置主键 ID。 */
    public void setId(Long id) {
        this.id = id;
    }

    /** 获取字幕 ID。 */
    public Long getSubtitleId() {
        return subtitleId;
    }

    /** 设置字幕 ID。 */
    public void setSubtitleId(Long subtitleId) {
        this.subtitleId = subtitleId;
    }

    /** 获取剧集 ID。 */
    public Long getEpisodeId() {
        return episodeId;
    }

    /** 设置剧集 ID。 */
    public void setEpisodeId(Long episodeId) {
        this.episodeId = episodeId;
    }

    /** 获取短剧 ID。 */
    public Long getDramaId() {
        return dramaId;
    }

    /** 设置短剧 ID。 */
    public void setDramaId(Long dramaId) {
        this.dramaId = dramaId;
    }

    /** 获取集数序号。 */
    public Integer getEpisodeNo() {
        return episodeNo;
    }

    /** 设置集数序号。 */
    public void setEpisodeNo(Integer episodeNo) {
        this.episodeNo = episodeNo;
    }

    /** 获取开始时间（毫秒）。 */
    public Integer getStartMs() {
        return startMs;
    }

    /** 设置开始时间（毫秒）。 */
    public void setStartMs(Integer startMs) {
        this.startMs = startMs;
    }

    /** 获取结束时间（毫秒）。 */
    public Integer getEndMs() {
        return endMs;
    }

    /** 设置结束时间（毫秒）。 */
    public void setEndMs(Integer endMs) {
        this.endMs = endMs;
    }

    /** 获取字幕文本。 */
    public String getText() {
        return text;
    }

    /** 设置字幕文本。 */
    public void setText(String text) {
        this.text = text;
    }

    /** 获取状态。 */
    public Integer getStatus() {
        return status;
    }

    /** 设置状态。 */
    public void setStatus(Integer status) {
        this.status = status;
    }
}
