package com.msd.interaction.mapper;

import com.msd.interaction.entity.RecommendationEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * 推荐行为事件 Mapper。
 */
public interface RecommendationEventMapper {

    /**
     * 幂等写入一条事件；同一用户、请求、短剧、事件类型只保留一条。
     */
    @Insert("""
            INSERT IGNORE INTO t_recommendation_event
                (request_id, user_id, drama_id, event_type, position)
            VALUES
                (#{requestId}, #{userId}, #{dramaId}, #{eventType}, #{position})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(RecommendationEvent event);
}
