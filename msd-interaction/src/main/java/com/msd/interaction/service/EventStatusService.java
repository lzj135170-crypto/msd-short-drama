package com.msd.interaction.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.mapper.EventConsumeLogMapper;
import com.msd.interaction.mapper.OutboxEventMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件状态查询：校验事件归属，返回投递状态和各消费组处理情况。
 */
@Service
public class EventStatusService {

    // outbox Mapper
    private final OutboxEventMapper outboxEventMapper;
    // 消费幂等日志 Mapper
    private final EventConsumeLogMapper consumeLogMapper;

    /**
     * 注入 outbox 和消费日志 Mapper。
     */
    public EventStatusService(OutboxEventMapper outboxEventMapper,
                              EventConsumeLogMapper consumeLogMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.consumeLogMapper = consumeLogMapper;
    }

    /**
     * 查询事件状态；事件不存在或不属于当前用户时统一 404。
     */
    public EventStatus get(Long userId, String eventId) {
        // 1. 未登录不查询事件
        if (userId == null || userId <= 0) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        OutboxEvent event = outboxEventMapper.selectByEventId(eventId);
        // 2. 不泄露其他用户的事件是否存在
        if (event == null || event.getUserId() == null || !event.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "事件不存在");
        }

        // 3. 返回发送状态和已消费组
        int status = event.getStatus() == null ? 0 : event.getStatus();
        return new EventStatus(
                event.getEventId(),
                status,
                statusName(status),
                event.getEventType(),
                event.getRetryCount() == null ? 0 : event.getRetryCount(),
                event.getSentAt(),
                event.getLastError(),
                consumeLogMapper.selectGroups(eventId));
    }

    /**
     * 数字状态转可读名称。
     */
    private String statusName(int status) {
        return switch (status) {
            case 0 -> "PENDING";
            case 1 -> "SENT";
            case 2 -> "DEAD";
            default -> "UNKNOWN";
        };
    }

    /**
     * 事件状态响应。
     */
    public record EventStatus(
            String eventId,
            int status,
            String statusName,
            String eventType,
            int retryCount,
            LocalDateTime sentAt,
            String lastError,
            List<String> consumedGroups) {
    }
}
