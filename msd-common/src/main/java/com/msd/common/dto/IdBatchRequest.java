package com.msd.common.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量 ID 查询请求。
 *
 * <p>用于用户、短剧、剧集的内部批量接口；一次最多 100 个 ID。</p>
 *
 * @param ids 待查询的 ID 列表，最多 100 个
 */
public record IdBatchRequest(
        @NotEmpty(message = "ids 不能为空")
        @Size(max = 100, message = "一次最多查询 100 个 ID")
        List<Long> ids
) {
}
