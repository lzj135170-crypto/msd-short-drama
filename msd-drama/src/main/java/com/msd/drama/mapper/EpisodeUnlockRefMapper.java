package com.msd.drama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.drama.entity.EpisodeUnlockRef;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 解锁引用 Mapper。
 */
public interface EpisodeUnlockRefMapper extends BaseMapper<EpisodeUnlockRef> {

    /**
     * 幂等插入引用，unlock_id 已存在时返回 0。
     */
    @Insert("INSERT IGNORE INTO t_episode_unlock_ref (unlock_id, episode_id, user_id) "
            + "VALUES (#{unlockId}, #{episodeId}, #{userId})")
    int insertIgnore(@Param("unlockId") Long unlockId,
                     @Param("episodeId") Long episodeId,
                     @Param("userId") Long userId);
}
