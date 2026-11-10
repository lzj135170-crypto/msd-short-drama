package com.msd.interaction.mapper;

import com.msd.interaction.dto.InteractionStatsRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 个人中心互动计数聚合查询。
 */
public interface InteractionStatsMapper {

    /**
     * 单条 SQL 聚合点赞短剧、收藏、评论和历史四项计数。
     */
    @Select("""
            SELECT
                (SELECT COUNT(*) FROM t_user_like
                 WHERE user_id = #{userId} AND target_type = 1) AS liked_drama_count,
                (SELECT COUNT(*) FROM t_user_favorite
                 WHERE user_id = #{userId}) AS favorite_count,
                (SELECT COUNT(*) FROM t_comment
                 WHERE user_id = #{userId} AND status <> 3) AS comment_count,
                (SELECT COUNT(*) FROM t_watch_history
                 WHERE user_id = #{userId}) AS history_count
            """)
    InteractionStatsRow selectStats(@Param("userId") Long userId);
}
