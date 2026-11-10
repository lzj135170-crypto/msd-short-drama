package com.msd.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.interaction.entity.AiFeedback;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 反馈 Mapper。
 */
public interface AiFeedbackMapper extends BaseMapper<AiFeedback> {

    /**
     * 幂等插入反馈，返回 1 表示新插入，0 表示已存在。
     */
    @Insert("INSERT IGNORE INTO t_ai_feedback "
            + "(user_id, scene, request_id, rating, reason, `comment`, target_id, session_id) "
            + "VALUES (#{userId}, #{scene}, #{requestId}, #{rating}, #{reason}, "
            + "#{comment}, #{targetId}, #{sessionId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(AiFeedback feedback);

    /**
     * 按用户、场景和 requestId 查询已存在的反馈。
     */
    @Select("SELECT id, user_id, scene, request_id, rating, reason, `comment`, "
            + "target_id, session_id, create_time FROM t_ai_feedback "
            + "WHERE user_id = #{userId} AND scene = #{scene} AND request_id = #{requestId}")
    AiFeedback selectByUserSceneRequest(@Param("userId") Long userId,
                                        @Param("scene") String scene,
                                        @Param("requestId") String requestId);
}
