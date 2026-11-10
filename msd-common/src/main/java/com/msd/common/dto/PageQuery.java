package com.msd.common.dto;

import com.msd.common.constant.CommonConstants;

/**
 * 通用分页参数。
 *
 * <p>页码从 1 开始；不传时使用默认值，超过上限时按最大页大小截断。</p>
 *
 * @param pageNo   页码，从 1 开始
 * @param pageSize 每页条数，默认 20，最大 100
 */
public record PageQuery(int pageNo, int pageSize) {

    /**
     * 归一化页码和页大小，非法值回落到默认值。
     */
    public static PageQuery of(Integer pageNo, Integer pageSize) {
        // 1. 页码最小为 1
        int resolvedPageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;

        // 2. 页大小默认 20，最大 100
        int resolvedPageSize = pageSize == null || pageSize < 1
                ? CommonConstants.DEFAULT_PAGE_SIZE
                : Math.min(pageSize, CommonConstants.MAX_PAGE_SIZE);

        // 3. 返回归一化后的分页参数
        return new PageQuery(resolvedPageNo, resolvedPageSize);
    }

    /**
     * 计算 SQL 的 OFFSET。
     */
    public long offset() {
        return (long) (pageNo - 1) * pageSize;
    }
}
