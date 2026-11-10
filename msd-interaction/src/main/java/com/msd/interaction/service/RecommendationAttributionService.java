package com.msd.interaction.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.interaction.dto.RecommendationEventRequest;
import com.msd.interaction.dto.RecommendationEventResult;
import com.msd.interaction.dto.RecommendationExposureRequest;
import com.msd.interaction.dto.RecommendationExposureResult;
import com.msd.interaction.entity.RecommendationEvent;
import com.msd.interaction.entity.RecommendationExposure;
import com.msd.interaction.mapper.RecommendationEventMapper;
import com.msd.interaction.mapper.RecommendationExposureMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 推荐曝光与行为归因。
 *
 * <p>曝光由 content 服务在返回推荐结果后上报；点击、播放、完播和负反馈由前端上报。
 * 所有写入按请求 ID 幂等，重复上报不会放大指标。</p>
 */
@Service
public class RecommendationAttributionService {

    /** 曝光场景默认值。 */
    private static final String DEFAULT_SCENE = "HOME_FEED";
    /** 理由最大长度，与表字段一致。 */
    private static final int MAX_REASON_CHARS = 256;

    // 曝光 Mapper
    private final RecommendationExposureMapper exposureMapper;
    // 行为事件 Mapper
    private final RecommendationEventMapper eventMapper;

    /**
     * 注入曝光和事件 Mapper。
     */
    public RecommendationAttributionService(RecommendationExposureMapper exposureMapper,
                                            RecommendationEventMapper eventMapper) {
        this.exposureMapper = exposureMapper;
        this.eventMapper = eventMapper;
    }

    /**
     * 批量写入曝光，返回实际新增条数。
     */
    @Transactional
    public RecommendationExposureResult recordExposures(RecommendationExposureRequest request) {
        // 1. 空列表直接返回
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return new RecommendationExposureResult(0);
        }
        int recorded = 0;
        // 2. 逐条幂等写入；单条重复不影响整批
        for (RecommendationExposureRequest.Item item : request.items()) {
            if (item == null || item.dramaId() == null || item.dramaId() <= 0) {
                continue;
            }
            RecommendationExposure exposure = new RecommendationExposure();
            exposure.setRequestId(request.requestId());
            exposure.setUserId(request.userId());
            exposure.setScene(normalize(request.scene(), DEFAULT_SCENE, 32));
            exposure.setAlgorithm(normalize(request.algorithm(), "", 32));
            exposure.setExperimentId(normalize(request.experimentId(), "", 64));
            exposure.setVariant(normalize(request.variant(), "", 32));
            exposure.setDramaId(item.dramaId());
            exposure.setPosition(item.position() == null ? 0 : Math.max(item.position(), 0));
            exposure.setReason(normalize(item.reason(), "", MAX_REASON_CHARS));
            recorded += exposureMapper.insertIgnore(exposure);
        }
        return new RecommendationExposureResult(recorded);
    }

    /**
     * 写入一条推荐行为事件。
     */
    @Transactional
    public RecommendationEventResult recordEvent(Long userId, RecommendationEventRequest request) {
        // 1. 用户和事件类型必须合法
        if (userId == null || userId <= 0) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (request == null || request.eventType() == null
                || request.eventType() < 1 || request.eventType() > 4) {
            throw new BizException(ResultCode.PARAM_ERROR, "事件类型不合法");
        }
        // 2. 幂等写入，重复点击/重复上报返回成功
        RecommendationEvent event = new RecommendationEvent();
        event.setRequestId(request.requestId());
        event.setUserId(userId);
        event.setDramaId(request.dramaId());
        event.setEventType(request.eventType());
        event.setPosition(request.position() == null ? 0 : Math.max(request.position(), 0));
        eventMapper.insertIgnore(event);
        return new RecommendationEventResult(true);
    }

    /**
     * 归一化可空字符串并截断。
     */
    private String normalize(String value, String defaultValue, int maxChars) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
