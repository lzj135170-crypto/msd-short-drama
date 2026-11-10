package com.msd.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.interaction.entity.OutboxEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事务性发件箱 Mapper。
 */
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 认领一批待发送事件，写入 claim_token 和租约。
     *
     * <p>MySQL UPDATE 不支持 SKIP LOCKED，短事务内先认领再按 token 查询；
     * locked_until 过期后允许下一轮重新认领，保证实例崩溃后可恢复。</p>
     */
    @Update("UPDATE t_outbox_event SET claim_token = #{token}, "
            + "locked_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW(3)), update_time = NOW(3) "
            + "WHERE status = 0 AND next_retry_time <= NOW(3) "
            + "AND (locked_until IS NULL OR locked_until < NOW(3)) "
            + "ORDER BY id LIMIT #{batchSize}")
    int claimPending(@Param("batchSize") int batchSize,
                     @Param("token") String token,
                     @Param("leaseSeconds") int leaseSeconds);

    /**
     * 查询当前实例刚认领的事件。
     */
    @Select("SELECT * FROM t_outbox_event "
            + "WHERE claim_token = #{token} AND status = 0 ORDER BY id")
    List<OutboxEvent> selectClaimed(@Param("token") String token);

    /**
     * 按事件 ID 查询 outbox 状态。
     */
    @Select("SELECT * FROM t_outbox_event WHERE event_id = #{eventId}")
    OutboxEvent selectByEventId(@Param("eventId") String eventId);

    /**
     * 查询最近的 outbox 事件，供测试台展示投递状态。
     */
    @Select("SELECT * FROM t_outbox_event ORDER BY id DESC LIMIT #{limit}")
    List<OutboxEvent> selectRecent(@Param("limit") int limit);

    /**
     * 标记发送成功，只有仍持有 claim token 的实例可以更新。
     */
    @Update("UPDATE t_outbox_event SET status = 1, sent_at = NOW(3), last_error = '', "
            + "claim_token = NULL, locked_until = NULL "
            + "WHERE id = #{id} AND claim_token = #{token} AND status = 0")
    int markSent(@Param("id") Long id, @Param("token") String token);

    /**
     * 记录失败并安排下次重试，释放认领。
     */
    @Update("UPDATE t_outbox_event SET retry_count = #{retryCount}, "
            + "next_retry_time = #{nextRetryTime}, last_error = #{lastError}, "
            + "claim_token = NULL, locked_until = NULL "
            + "WHERE id = #{id} AND claim_token = #{token} AND status = 0")
    int markRetry(@Param("id") Long id,
                  @Param("token") String token,
                  @Param("retryCount") int retryCount,
                  @Param("nextRetryTime") LocalDateTime nextRetryTime,
                  @Param("lastError") String lastError);

    /**
     * 超过最大重试次数，转入死信并释放认领。
     */
    @Update("UPDATE t_outbox_event SET status = 2, retry_count = #{retryCount}, "
            + "last_error = #{lastError}, claim_token = NULL, locked_until = NULL "
            + "WHERE id = #{id} AND claim_token = #{token} AND status = 0")
    int markDead(@Param("id") Long id,
                 @Param("token") String token,
                 @Param("retryCount") int retryCount,
                 @Param("lastError") String lastError);

    /**
     * 当前待发送事件数，用于积压指标。
     */
    @Select("SELECT COUNT(*) FROM t_outbox_event WHERE status = 0")
    long countPending();

    /**
     * 最老待发送事件的创建时间，用于事件年龄指标。
     */
    @Select("SELECT MIN(create_time) FROM t_outbox_event WHERE status = 0")
    LocalDateTime selectOldestPendingCreateTime();
}
