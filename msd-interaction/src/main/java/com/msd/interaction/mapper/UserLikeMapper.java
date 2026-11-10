package com.msd.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.interaction.entity.UserLike;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 点赞 Mapper。
 */
public interface UserLikeMapper extends BaseMapper<UserLike> {

    /**
     * 幂等插入点赞，返回 1 表示新插入，0 表示已存在。
     * 使用 INSERT IGNORE 而不是 ON DUPLICATE KEY UPDATE id=id：
     * MySQL Connector/J 默认返回 found rows，后者在重复时会返回 1。
     */
    @Insert("INSERT IGNORE INTO t_user_like (user_id, target_type, target_id) "
            + "VALUES (#{userId}, #{targetType}, #{targetId})")
    int insertIgnore(@Param("userId") Long userId,
                     @Param("targetType") Integer targetType,
                     @Param("targetId") Long targetId);

    /**
     * 删除点赞，返回影响行数。
     */
    @Delete("DELETE FROM t_user_like WHERE user_id = #{userId} "
            + "AND target_type = #{targetType} AND target_id = #{targetId}")
    int deleteByUserAndTarget(@Param("userId") Long userId,
                              @Param("targetType") Integer targetType,
                              @Param("targetId") Long targetId);

    /**
     * 查询用户对目标的点赞记录数，用于测试台展示当前状态。
     */
    @Select("SELECT COUNT(*) FROM t_user_like WHERE user_id = #{userId} "
            + "AND target_type = #{targetType} AND target_id = #{targetId}")
    int countByUserAndTarget(@Param("userId") Long userId,
                             @Param("targetType") Integer targetType,
                             @Param("targetId") Long targetId);

    /**
     * 分页查询用户点赞的短剧，按点赞时间倒序。
     */
    @Select("SELECT id, user_id, target_type, target_id, create_time FROM t_user_like "
            + "WHERE user_id = #{userId} AND target_type = 1 "
            + "ORDER BY create_time DESC, id DESC LIMIT #{offset}, #{pageSize}")
    List<UserLike> selectLikedDramas(@Param("userId") Long userId,
                                     @Param("offset") long offset,
                                     @Param("pageSize") int pageSize);

    /**
     * 统计用户点赞的短剧数。
     */
    @Select("SELECT COUNT(*) FROM t_user_like WHERE user_id = #{userId} AND target_type = 1")
    long countLikedDramas(@Param("userId") Long userId);
}
