package com.msd.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 创建充值订单请求。
 *
 * @param planId          档位 ID
 * @param clientRequestId 客户端幂等键，重复请求返回同一订单
 */
public record CreateRechargeOrderRequest(
        @NotNull(message = "充值档位不能为空")
        @Positive(message = "充值档位不合法")
        Long planId,

        @NotBlank(message = "客户端请求 ID 不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{8,64}", message = "客户端请求 ID 格式不正确")
        String clientRequestId
) {
}
