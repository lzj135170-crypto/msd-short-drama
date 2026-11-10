package com.msd.interaction.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.interaction.dto.OutboxStatus;
import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.mapper.OutboxEventMapper;
import com.msd.interaction.mapper.UserFavoriteMapper;
import com.msd.interaction.mapper.UserLikeMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 互动只读查询，供测试台观察 outbox 和用户状态。
 */
@Service
public class InteractionQueryService {

    // outbox 事件 Mapper
    private final OutboxEventMapper outboxEventMapper;
    // 点赞记录 Mapper
    private final UserLikeMapper userLikeMapper;
    // 收藏记录 Mapper
    private final UserFavoriteMapper userFavoriteMapper;

    /**
     * 注入 outbox、点赞和收藏 Mapper。
     */
    public InteractionQueryService(OutboxEventMapper outboxEventMapper,
                                   UserLikeMapper userLikeMapper,
                                   UserFavoriteMapper userFavoriteMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.userLikeMapper = userLikeMapper;
        this.userFavoriteMapper = userFavoriteMapper;
    }

    /**
     * 按事件 ID 查询 outbox 状态。
     */
    public OutboxStatus getOutboxStatus(String eventId) {
        // 1. 查询事件，不存在时返回 404
        OutboxEvent event = outboxEventMapper.selectByEventId(eventId);
        if (event == null) {
            throw new BizException(ResultCode.NOT_FOUND, "outbox 事件不存在");
        }
        // 2. 转成对外的状态 DTO
        return OutboxStatus.from(event);
    }

    /**
     * 查询最近的 outbox 事件。
     */
    public List<OutboxStatus> recentOutbox(int limit) {
        // 1. 限制单次返回数量，避免测试台拉取过多数据
        int safeLimit = Math.max(1, Math.min(limit, 50));
        // 2. 查询并转换
        return outboxEventMapper.selectRecent(safeLimit).stream()
                .map(OutboxStatus::from)
                .toList();
    }

    /**
     * 查询用户是否已点赞目标。
     */
    public boolean isLiked(Long userId, Integer targetType, Long targetId) {
        return userLikeMapper.countByUserAndTarget(userId, targetType, targetId) > 0;
    }

    /**
     * 查询用户是否已收藏短剧。
     */
    public boolean isFavorited(Long userId, Long dramaId) {
        return userFavoriteMapper.countByUserAndDrama(userId, dramaId) > 0;
    }
}
