package com.msd.interaction.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * interaction 侧 Kafka 消费幂等日志。
 */
public interface EventConsumeLogMapper {

    /**
     * 幂等记录消费事件，返回 1 表示首次消费，0 表示重复。
     */
    @Insert("INSERT IGNORE INTO t_event_consume_log (consumer_group, event_id, topic) "
            + "VALUES (#{consumerGroup}, #{eventId}, #{topic})")
    int insertIgnore(@Param("consumerGroup") String consumerGroup,
                     @Param("eventId") String eventId,
                     @Param("topic") String topic);

    /**
     * 查询事件已被哪些消费组处理，用于事件状态接口。
     */
    @Select("SELECT consumer_group FROM t_event_consume_log "
            + "WHERE event_id = #{eventId} ORDER BY consumer_group")
    List<String> selectGroups(@Param("eventId") String eventId);

    /**
     * 批量查询已经消费过的 eventId，供批量消费者去重。
     */
    @Select("""
            <script>
            SELECT event_id
            FROM t_event_consume_log
            WHERE consumer_group = #{consumerGroup}
              AND event_id IN
              <foreach collection="eventIds" item="eventId" open="(" separator="," close=")">
                #{eventId}
              </foreach>
            </script>
            """)
    List<String> selectExistingEventIds(@Param("consumerGroup") String consumerGroup,
                                        @Param("eventIds") List<String> eventIds);

    /**
     * 批量写入消费日志，返回实际插入条数。
     */
    @Insert("""
            <script>
            INSERT IGNORE INTO t_event_consume_log (consumer_group, event_id, topic)
            VALUES
            <foreach collection="records" item="record" separator=",">
              (#{record.consumerGroup}, #{record.eventId}, #{record.topic})
            </foreach>
            </script>
            """)
    int insertIgnoreBatch(@Param("records") List<ConsumeLogRecord> records);

    /**
     * 批量消费日志行。
     */
    record ConsumeLogRecord(String consumerGroup, String eventId, String topic) {
    }
}
