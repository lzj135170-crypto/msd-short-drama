package com.msd.interaction.mapper;

import com.msd.interaction.entity.RecommendationExposure;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * 推荐曝光 Mapper。
 */
public interface RecommendationExposureMapper {

    /**
     * 幂等写入一条曝光；重复 requestId + dramaId 直接忽略。
     */
    @Insert("""
            INSERT IGNORE INTO t_recommendation_exposure
                (request_id, user_id, scene, algorithm, experiment_id, variant,
                 drama_id, position, reason)
            VALUES
                (#{requestId}, #{userId}, #{scene}, #{algorithm}, #{experimentId}, #{variant},
                 #{dramaId}, #{position}, #{reason})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(RecommendationExposure exposure);
}
