package com.msd.interaction.service;

import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.mapper.OutboxEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Outbox 短事务认领：认领和读取在同一事务内完成，发送在事务外执行。
 */
@Service
public class OutboxClaimService {

    // outbox 事件 Mapper
    private final OutboxEventMapper outboxEventMapper;

    /**
     * 注入 outbox Mapper。
     */
    public OutboxClaimService(OutboxEventMapper outboxEventMapper) {
        this.outboxEventMapper = outboxEventMapper;
    }

    /**
     * 认领一批事件并返回当前 token 对应的行。
     */
    @Transactional
    public List<OutboxEvent> claim(int batchSize, String token, int leaseSeconds) {
        outboxEventMapper.claimPending(batchSize, token, leaseSeconds);
        return outboxEventMapper.selectClaimed(token);
    }
}
