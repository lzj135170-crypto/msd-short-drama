package com.msd.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 短剧标签实体，对应 t_drama_tag。
 */
@TableName("t_drama_tag")
public class DramaTag {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 标签名称
    private String name;
    // 状态：0 停用，1 启用
    private Integer status;

    /**
     * 获取主键 ID。
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置主键 ID。
     */
    public void setId(Long id) {
        this.id = id;
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

    /**
     * 获取状态，1 表示启用。
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * 设置状态。
     */
    public void setStatus(Integer status) {
        this.status = status;
    }
}
