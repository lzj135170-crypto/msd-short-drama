package com.msd.content.client;

import com.msd.common.constant.CommonConstants;
import com.msd.common.constant.ServiceNames;
import com.msd.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 订单服务只读接口，用于校验伴看付费集权限。
 */
@FeignClient(name = ServiceNames.ORDER, contextId = "orderAccessClient",
        path = "/internal/orders", configuration = ContentFeignConfig.class)
public interface OrderAccessClient {

    /**
     * 查询用户对某集的解锁状态。
     */
    @GetMapping("/episodes/{episodeId}/unlock-state")
    Result<OrderUnlockState> unlockState(
            @RequestHeader(CommonConstants.HEADER_USER_ID) Long userId,
            @PathVariable("episodeId") Long episodeId);

    /**
     * 查询用户在某部短剧下已解锁的付费集。
     */
    @GetMapping("/dramas/{dramaId}/unlocks")
    Result<OrderDramaUnlocks> dramaUnlocks(
            @RequestHeader(CommonConstants.HEADER_USER_ID) Long userId,
            @PathVariable("dramaId") Long dramaId);
}
