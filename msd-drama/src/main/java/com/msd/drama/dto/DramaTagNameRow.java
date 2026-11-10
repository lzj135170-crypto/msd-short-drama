package com.msd.drama.dto;

/**
 * 短剧标签批量查询行。
 *
 * <p>使用普通 POJO，避免依赖 MyBatis 构造器参数顺序映射。</p>
 */
public class DramaTagNameRow {

    // 短剧 ID
    private Long dramaId;
    // 标签名称
    private String name;

    /**
     * 获取短剧 ID。
     */
    public Long getDramaId() {
        return dramaId;
    }

    /**
     * 设置短剧 ID。
     */
    public void setDramaId(Long dramaId) {
        this.dramaId = dramaId;
    }

    /**
     * 获取标签名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 设置标签名称。
     */
    public void setName(String name) {
        this.name = name;
    }
}
