package com.msd.interaction.mapper;

import com.msd.interaction.dto.UserBehaviorRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户行为聚合 Mapper，只读查询 interaction 自己的表。
 */
public interface UserBehaviorMapper {

    /**
     * 按短剧聚合收藏、点赞和观看权重，同一部剧取最大权重。
     *
     * @param userId 用户 ID
     * @param since  时间窗口起点
     * @return 按权重、最近时间倒序的行为行
     */
    @Select("""
            SELECT drama_id AS dramaId,
                   MAX(weight) AS weight,
                   MAX(lastTime) AS lastTime
            FROM (
                SELECT drama_id, 3.0 AS weight, create_time AS lastTime
                FROM t_user_favorite
                WHERE user_id = #{userId} AND create_time >= #{since}
                UNION ALL
                SELECT target_id AS drama_id, 2.0 AS weight, create_time AS lastTime
                FROM t_user_like
                WHERE user_id = #{userId} AND target_type = 1 AND create_time >= #{since}
                UNION ALL
                SELECT drama_id,
                       CASE WHEN finished = 1 THEN 1.5 ELSE 1.0 END AS weight,
                       last_watch_time AS lastTime
                FROM t_watch_history
                WHERE user_id = #{userId} AND last_watch_time >= #{since}
            ) signals
            GROUP BY drama_id
            ORDER BY weight DESC, lastTime DESC
            """)
    List<UserBehaviorRow> selectWeights(@Param("userId") Long userId,
                                        @Param("since") LocalDateTime since);

    /**
     * 查询用户最近收藏的短剧 ID。
     */
    @Select("SELECT drama_id FROM t_user_favorite "
            + "WHERE user_id = #{userId} ORDER BY create_time DESC, id DESC LIMIT #{limit}")
    List<Long> selectFavoriteDramaIds(@Param("userId") Long userId,
                                      @Param("limit") int limit);

    /**
     * 查询最近观看的短剧，按每部剧最后一次观看时间倒序。
     */
    @Select("""
            SELECT drama_id
            FROM (
                SELECT drama_id, MAX(last_watch_time) AS last_time
                FROM t_watch_history
                WHERE user_id = #{userId}
                GROUP BY drama_id
            ) watched
            ORDER BY last_time DESC, drama_id DESC
            LIMIT #{limit}
            """)
    List<Long> selectContinueDramaIds(@Param("userId") Long userId,
                                      @Param("limit") int limit);

    /**
     * 共同观看召回：看过同一部剧的其他用户还看了什么。
     *
     * <p>只作为候选来源，不去重用户；最终由内容服务按短剧聚合得分。</p>
     */
    @Select("""
            SELECT other.drama_id AS dramaId,
                   COUNT(*) AS weight,
                   MAX(other.last_watch_time) AS lastTime
            FROM t_watch_history mine
            JOIN t_watch_history other
              ON other.user_id = mine.user_id
             AND other.drama_id <> mine.drama_id
            WHERE mine.user_id = #{userId}
              AND mine.last_watch_time >= #{since}
              AND other.last_watch_time >= #{since}
            GROUP BY other.drama_id
            ORDER BY weight DESC, lastTime DESC, other.drama_id DESC
            LIMIT #{limit}
            """)
    List<UserBehaviorRow> selectCoOccurrenceDramas(@Param("userId") Long userId,
                                                   @Param("since") LocalDateTime since,
                                                   @Param("limit") int limit);

    /**
     * 查询用户最近标记“不感兴趣”的短剧。
     */
    @Select("""
            SELECT drama_id
            FROM t_recommendation_event
            WHERE user_id = #{userId} AND event_type = 4
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<Long> selectDislikedDramaIds(@Param("userId") Long userId,
                                      @Param("limit") int limit);
}
