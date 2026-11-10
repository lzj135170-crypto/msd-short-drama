package com.msd.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.order.entity.EpisodeUnlock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 剧集解锁权益 Mapper。
 */
public interface EpisodeUnlockMapper extends BaseMapper<EpisodeUnlock> {

    /**
     * 查询用户对某集的解锁记录。
     */
    @Select("SELECT id, user_id, drama_id, episode_id, points_cost, create_time "
            + "FROM t_episode_unlock WHERE user_id = #{userId} AND episode_id = #{episodeId}")
    EpisodeUnlock selectByUserAndEpisode(@Param("userId") Long userId,
                                         @Param("episodeId") Long episodeId);

    /**
     * 加锁查询解锁记录，用于并发重复解锁时读取已提交的最新行。
     */
    @Select("SELECT id, user_id, drama_id, episode_id, points_cost, create_time "
            + "FROM t_episode_unlock WHERE user_id = #{userId} AND episode_id = #{episodeId} "
            + "FOR UPDATE")
    EpisodeUnlock selectByUserAndEpisodeForUpdate(@Param("userId") Long userId,
                                                  @Param("episodeId") Long episodeId);

    /**
     * 查询用户在某部短剧下已解锁的剧集，按解锁时间升序。
     */
    @Select("SELECT id, user_id, drama_id, episode_id, points_cost, create_time "
            + "FROM t_episode_unlock WHERE user_id = #{userId} AND drama_id = #{dramaId} "
            + "ORDER BY create_time, id")
    List<EpisodeUnlock> selectByUserAndDrama(@Param("userId") Long userId,
                                             @Param("dramaId") Long dramaId);

    /**
     * 分页查询用户已解锁剧集，按解锁时间倒序。
     */
    @Select("SELECT id, user_id, drama_id, episode_id, points_cost, create_time "
            + "FROM t_episode_unlock WHERE user_id = #{userId} "
            + "ORDER BY create_time DESC, id DESC LIMIT #{offset}, #{pageSize}")
    List<EpisodeUnlock> selectPageByUser(@Param("userId") Long userId,
                                         @Param("offset") long offset,
                                         @Param("pageSize") int pageSize);

    /**
     * 统计用户已解锁剧集数量。
     */
    @Select("SELECT COUNT(*) FROM t_episode_unlock WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);
}
