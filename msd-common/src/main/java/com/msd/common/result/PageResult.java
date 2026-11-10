package com.msd.common.result;

import java.io.Serializable;
import java.util.List;

/**
 * 分页响应体。
 */
public class PageResult<T> implements Serializable {

    // 当前页码
    private long pageNo;
    // 每页条数
    private long pageSize;
    // 总记录数
    private long total;
    // 当前页数据
    private List<T> records;

    /**
     * 供 JSON 反序列化使用。
     */
    public PageResult() {
    }

    /**
     * 全参构造。
     */
    public PageResult(long pageNo, long pageSize, long total, List<T> records) {
        this.pageNo = pageNo;
        this.pageSize = pageSize;
        this.total = total;
        this.records = records;
    }

    /**
     * 创建分页响应。
     */
    public static <T> PageResult<T> of(long pageNo, long pageSize, long total, List<T> records) {
        return new PageResult<>(pageNo, pageSize, total, records);
    }

    /**
     * 创建空分页响应。
     */
    public static <T> PageResult<T> empty(long pageNo, long pageSize) {
        return new PageResult<>(pageNo, pageSize, 0, List.of());
    }

    /**
     * 获取当前页码。
     */
    public long getPageNo() {
        return pageNo;
    }

    /**
     * 设置当前页码。
     */
    public void setPageNo(long pageNo) {
        this.pageNo = pageNo;
    }

    /**
     * 获取每页条数。
     */
    public long getPageSize() {
        return pageSize;
    }

    /**
     * 设置每页条数。
     */
    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * 获取总记录数。
     */
    public long getTotal() {
        return total;
    }

    /**
     * 设置总记录数。
     */
    public void setTotal(long total) {
        this.total = total;
    }

    /**
     * 获取当前页数据。
     */
    public List<T> getRecords() {
        return records;
    }

    /**
     * 设置当前页数据。
     */
    public void setRecords(List<T> records) {
        this.records = records;
    }
}
