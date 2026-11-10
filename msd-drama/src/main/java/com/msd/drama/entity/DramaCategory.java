package com.msd.drama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 短剧分类实体，对应 t_drama_category。
 */
@TableName("t_drama_category")
public class DramaCategory {

    // 主键 ID
    @TableId(type = IdType.AUTO)
    private Long id;
    // 分类名称
    private String name;
    // 排序值，越小越靠前
    private Integer sort;
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
     * 获取分类名称。
     */
    public String getName() {
        return name;
    }

    /**
     * 设置分类名称。
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取排序值。
     */
    public Integer getSort() {
        return sort;
    }

    /**
     * 设置排序值。
     */
    public void setSort(Integer sort) {
        this.sort = sort;
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
