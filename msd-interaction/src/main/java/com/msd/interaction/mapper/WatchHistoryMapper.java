package com.msd.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.interaction.entity.WatchHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 观看历史 Mapper。
 */
public interface WatchHistoryMapper extends BaseMapper<WatchHistory> {

    /**
     * 按 (user_id, episode_id) upsert。
     * 返回 1 表示首次插入，2 表示更新，0 表示值未变化。
     */
    @Insert("INSERT INTO t_watch_history "
            + "(user_id, drama_id, episode_id, position_ms, duration_ms, finished, last_watch_time) "
            + "VALUES (#{userId}, #{dramaId}, #{episodeId}, #{positionMs}, #{durationMs}, "
            + "#{finished}, NOW(3)) "
            + "ON DUPLICATE KEY UPDATE position_ms = VALUES(position_ms), "
            + "duration_ms = VALUES(duration_ms), finished = VALUES(finished), "
            + "last_watch_time = NOW(3)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(WatchHistory history);

    /**
     * 分页查询观看历史，按最近观看时间倒序。
     */
    @Select("SELECT id, user_id, drama_id, episode_id, position_ms, duration_ms, finished, "
            + "last_watch_time, create_time, update_time FROM t_watch_history "
            + "WHERE user_id = #{userId} ORDER BY last_watch_time DESC, id DESC "
            + "LIMIT #{offset}, #{pageSize}")
    List<WatchHistory> selectByUser(@Param("userId") Long userId,
                                    @Param("offset") long offset,
                                    @Param("pageSize") int pageSize);

    /**
     * 统计用户观看历史条数。
     */
    @Select("SELECT COUNT(*) FROM t_watch_history WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);

    /**
     * 每部短剧取最近一条观看记录，用于首页继续观看。
     */
    @Select("""
            SELECT * FROM (
                SELECT h.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY h.drama_id
                           ORDER BY h.last_watch_time DESC, h.id DESC
                       ) AS row_num
                FROM t_watch_history h
                WHERE h.user_id = #{userId}
            ) ranked
            WHERE ranked.row_num = 1
            ORDER BY ranked.last_watch_time DESC, ranked.id DESC
            LIMIT #{limit}
            """)
    List<WatchHistory> selectLatestPerDrama(@Param("userId") Long userId,
                                            @Param("limit") int limit);

    /**
     * 删除用户某集的历史记录。
     */
    @Delete("DELETE FROM t_watch_history WHERE user_id = #{userId} AND episode_id = #{episodeId}")
    int deleteByUserAndEpisode(@Param("userId") Long userId, @Param("episodeId") Long episodeId);

    /**
     * 清空用户全部观看历史。
     */
    @Delete("DELETE FROM t_watch_history WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") Long userId);
}
