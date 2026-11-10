package com.msd.interaction.service;

import com.msd.common.dto.UserBehaviorDTO;
import com.msd.interaction.dto.UserBehaviorRow;
import com.msd.interaction.mapper.UserBehaviorMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户行为聚合测试。
 */
class UserBehaviorServiceTest {

    /**
     * 按短剧聚合权重、最近互动和收藏 ID。
     */
    @Test
    void aggregatesWeightsAndRecentDramas() {
        UserBehaviorMapper mapper = mock(UserBehaviorMapper.class);
        when(mapper.selectWeights(eq(1006L), any(LocalDateTime.class))).thenReturn(List.of(
                new UserBehaviorRow(5004L, 3.0, LocalDateTime.of(2026, 10, 20, 10, 0)),
                new UserBehaviorRow(5005L, 2.0, LocalDateTime.of(2026, 10, 22, 10, 0)),
                new UserBehaviorRow(5001L, 1.5, LocalDateTime.of(2026, 10, 21, 10, 0))));
        when(mapper.selectFavoriteDramaIds(1006L, 20)).thenReturn(List.of(5004L));

        UserBehaviorService service = new UserBehaviorService(mapper, 90);
        UserBehaviorDTO result = service.getBehavior(1006L);

        assertThat(result.weights()).extracting(UserBehaviorDTO.DramaWeight::dramaId)
                .containsExactly(5004L, 5005L, 5001L);
        assertThat(result.weights()).extracting(UserBehaviorDTO.DramaWeight::weight)
                .containsExactly(3.0, 2.0, 1.5);
        assertThat(result.recentDramaIds()).containsExactly(5005L, 5001L, 5004L);
        assertThat(result.favoriteDramaIds()).containsExactly(5004L);
    }

    /**
     * Mapper 异常时返回空行为，避免影响推荐链路。
     */
    @Test
    void returnsEmptyWhenMapperFails() {
        UserBehaviorMapper mapper = mock(UserBehaviorMapper.class);
        when(mapper.selectWeights(eq(1006L), any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("db down"));

        UserBehaviorService service = new UserBehaviorService(mapper, 90);
        UserBehaviorDTO result = service.getBehavior(1006L);

        assertThat(result.weights()).isEmpty();
        assertThat(result.recentDramaIds()).isEmpty();
        assertThat(result.favoriteDramaIds()).isEmpty();
    }
}
