package com.msd.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.interaction.entity.UserFavorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 收藏 Mapper。
 */
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {

    /**
     * 幂等插入收藏，返回 1 表示新插入，0 表示已存在。
     * 使用 INSERT IGNORE，避免 Connector/J found-rows 语义把重复插入返回成 1。
     */
    @Insert("INSERT IGNORE INTO t_user_favorite (user_id, drama_id) VALUES (#{userId}, #{dramaId})")
    int insertIgnore(@Param("userId") Long userId, @Param("dramaId") Long dramaId);

    /**
     * 删除收藏，返回影响行数。
     */
    @Delete("DELETE FROM t_user_favorite WHERE user_id = #{userId} AND drama_id = #{dramaId}")
    int deleteByUserAndDrama(@Param("userId") Long userId, @Param("dramaId") Long dramaId);

    /**
     * 查询用户对短剧的收藏记录数，用于测试台展示当前状态。
     */
    @Select("SELECT COUNT(*) FROM t_user_favorite WHERE user_id = #{userId} AND drama_id = #{dramaId}")
    int countByUserAndDrama(@Param("userId") Long userId, @Param("dramaId") Long dramaId);

    /**
     * 分页查询用户收藏，按收藏时间倒序。
     */
    @Select("SELECT id, user_id, drama_id, create_time FROM t_user_favorite "
            + "WHERE user_id = #{userId} ORDER BY create_time DESC, id DESC "
            + "LIMIT #{offset}, #{pageSize}")
    List<UserFavorite> selectByUser(@Param("userId") Long userId,
                                    @Param("offset") long offset,
                                    @Param("pageSize") int pageSize);

    /**
     * 统计用户收藏数。
     */
    @Select("SELECT COUNT(*) FROM t_user_favorite WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);
}
