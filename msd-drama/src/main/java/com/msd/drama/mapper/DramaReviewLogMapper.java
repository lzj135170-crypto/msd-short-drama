package com.msd.drama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.drama.entity.DramaReviewLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 短剧审核操作日志 Mapper。
 */
public interface DramaReviewLogMapper extends BaseMapper<DramaReviewLog> {

    /**
     * 查询某部短剧的最近审核日志。
     */
    @Select("""
            SELECT id, drama_id, action, from_status, to_status, operator_id,
                   source, remark, create_time
            FROM t_drama_review_log
            WHERE drama_id = #{dramaId}
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<DramaReviewLog> selectByDrama(@Param("dramaId") Long dramaId,
                                       @Param("limit") int limit);
}
