package com.msd.interaction.service;

import com.msd.interaction.dto.RecommendationEventRequest;
import com.msd.interaction.dto.RecommendationExposureRequest;
import com.msd.interaction.entity.RecommendationEvent;
import com.msd.interaction.mapper.RecommendationEventMapper;
import com.msd.interaction.mapper.RecommendationExposureMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推荐曝光与事件归因测试。
 */
class RecommendationAttributionServiceTest {

    private final RecommendationExposureMapper exposureMapper =
            mock(RecommendationExposureMapper.class);
    private final RecommendationEventMapper eventMapper =
            mock(RecommendationEventMapper.class);
    private final RecommendationAttributionService service =
            new RecommendationAttributionService(exposureMapper, eventMapper);

    /**
     * 曝光批量写入，重复项由 INSERT IGNORE 忽略。
     */
    @Test
    void recordsExposuresIdempotently() {
        when(exposureMapper.insertIgnore(any())).thenReturn(1, 0);
        RecommendationExposureRequest request = new RecommendationExposureRequest(
                1003L, "req-1", "HOME_FEED", "v2", "for-you-v2", "A",
                List.of(
                        new RecommendationExposureRequest.Item(5001L, 1, "因为你喜欢甜宠"),
                        new RecommendationExposureRequest.Item(5002L, 2, "本周热播")));

        int recorded = service.recordExposures(request).recorded();

        assertThat(recorded).isEqualTo(1);
        ArgumentCaptor<com.msd.interaction.entity.RecommendationExposure> captor =
                ArgumentCaptor.forClass(com.msd.interaction.entity.RecommendationExposure.class);
        verify(exposureMapper, org.mockito.Mockito.times(2)).insertIgnore(captor.capture());
        assertThat(captor.getAllValues().get(0).getRequestId()).isEqualTo("req-1");
        assertThat(captor.getAllValues().get(0).getDramaId()).isEqualTo(5001L);
    }

    /**
     * 点击和负反馈事件按用户幂等写入。
     */
    @Test
    void recordsUserEvent() {
        service.recordEvent(1003L, new RecommendationEventRequest(
                "req-1", 5001L, 4, 1));

        ArgumentCaptor<RecommendationEvent> captor =
                ArgumentCaptor.forClass(RecommendationEvent.class);
        verify(eventMapper).insertIgnore(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1003L);
        assertThat(captor.getValue().getEventType()).isEqualTo(4);
    }
}
