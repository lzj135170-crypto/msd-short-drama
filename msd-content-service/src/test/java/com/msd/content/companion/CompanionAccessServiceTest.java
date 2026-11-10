package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.result.Result;
import com.msd.content.client.OrderAccessClient;
import com.msd.content.client.OrderDramaUnlocks;
import com.msd.content.client.OrderUnlockState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 伴看付费集权限校验测试。
 */
class CompanionAccessServiceTest {

    private final OrderAccessClient orderClient = mock(OrderAccessClient.class);
    private final CompanionAccessService service = new CompanionAccessService(orderClient);

    /**
     * 免费集直接放行，不调用订单服务。
     */
    @Test
    void allowsFreeEpisodeWithoutOrderCall() {
        assertThat(service.canAccess(1003L, meta(6001L, 0))).isTrue();
        verify(orderClient, never()).unlockState(1003L, 6001L);
    }

    /**
     * 付费集已解锁时放行。
     */
    @Test
    void allowsUnlockedPaidEpisode() {
        when(orderClient.unlockState(1003L, 6001L))
                .thenReturn(Result.ok(new OrderUnlockState(6001L, true, 1L, 30L)));

        assertThat(service.canAccess(1003L, meta(6001L, 30))).isTrue();
    }

    /**
     * 付费集未解锁时拒绝。
     */
    @Test
    void rejectsLockedPaidEpisode() {
        when(orderClient.unlockState(1003L, 6002L))
                .thenReturn(Result.ok(new OrderUnlockState(6002L, false, 0L, 0L)));

        assertThat(service.canAccess(1003L, meta(6002L, 30))).isFalse();
    }

    /**
     * 订单服务不可用时，对付费集按 fail-closed 处理。
     */
    @Test
    void failsClosedWhenOrderUnavailable() {
        when(orderClient.unlockState(1003L, 6003L))
                .thenThrow(new IllegalStateException("order down"));

        assertThat(service.canAccess(1003L, meta(6003L, 30))).isFalse();
    }

    /**
     * 未登录用户只能访问免费集。
     */
    @Test
    void rejectsPaidEpisodeWithoutUser() {
        assertThat(service.canAccess(null, meta(6001L, 30))).isFalse();
    }

    /**
     * 批量查询前情解锁集合；订单失败时返回空集合，后续只保留免费集。
     */
    @Test
    void returnsUnlockedEpisodeIdsOrEmptyOnFailure() {
        when(orderClient.dramaUnlocks(1003L, 5001L))
                .thenReturn(Result.ok(new OrderDramaUnlocks(5001L, List.of(6003L, 6004L))));

        assertThat(service.unlockedEpisodeIds(1003L, 5001L))
                .containsExactlyInAnyOrder(6003L, 6004L);

        when(orderClient.dramaUnlocks(1004L, 5001L))
                .thenThrow(new IllegalStateException("order down"));
        assertThat(service.unlockedEpisodeIds(1004L, 5001L)).isEmpty();
    }

    /**
     * 构造剧集元信息。
     */
    private CompanionEpisodeDTO meta(Long episodeId, int pricePoints) {
        return new CompanionEpisodeDTO(
                episodeId, 5001L, "隐婚总裁的甜妻", 1, "第1集",
                "", 2, "简介", pricePoints);
    }
}
