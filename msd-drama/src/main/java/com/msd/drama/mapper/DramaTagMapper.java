package com.msd.drama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.drama.dto.DramaTagNameRow;
import com.msd.drama.entity.DramaTag;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 短剧标签 Mapper。
 */
public interface DramaTagMapper extends BaseMapper<DramaTag> {

    /**
     * 查询短剧关联的可见标签，按标签 ID 升序。
     */
    @Select("""
            SELECT t.id, t.name, t.status
            FROM t_drama_tag_rel r
            JOIN t_drama_tag t ON t.id = r.tag_id
            WHERE r.drama_id = #{dramaId} AND t.status = 1
            ORDER BY t.id
            """)
    List<DramaTag> selectVisibleByDramaId(@Param("dramaId") Long dramaId);

    /**
     * 查询启用中的标签名称，供 content-service 的 Rex 标签候选使用。
     */
    @Select("""
            SELECT DISTINCT name
            FROM t_drama_tag
            WHERE status = 1 AND name IS NOT NULL AND name <> ''
            ORDER BY name
            LIMIT 100
            """)
    List<String> selectVisibleNames();

    /**
     * 按短剧 ID 批量查询可见标签名称，避免 RAG 导出时的 N+1 查询。
     */
    @Select("""
            <script>
            SELECT r.drama_id, t.name
            FROM t_drama_tag_rel r
            JOIN t_drama_tag t ON t.id = r.tag_id
            WHERE t.status = 1
              AND r.drama_id IN
              <foreach collection="dramaIds" item="dramaId" open="(" separator="," close=")">#{dramaId}</foreach>
            ORDER BY r.drama_id, t.id
            </script>
            """)
    List<DramaTagNameRow> selectVisibleTagNamesByDramaIds(@Param("dramaIds") List<Long> dramaIds);
}
