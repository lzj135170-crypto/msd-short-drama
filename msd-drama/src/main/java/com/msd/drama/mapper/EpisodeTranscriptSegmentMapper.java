package com.msd.drama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.drama.entity.EpisodeTranscriptSegment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分集字幕片段 Mapper。
 */
public interface EpisodeTranscriptSegmentMapper extends BaseMapper<EpisodeTranscriptSegment> {

    /**
     * 删除某份字幕的全部片段，供重新导入覆盖。
     */
    @Delete("DELETE FROM t_episode_transcript_segment WHERE subtitle_id = #{subtitleId}")
    int deleteBySubtitleId(@Param("subtitleId") Long subtitleId);

    /**
     * 批量写入片段；字幕片段数量有限，单条 SQL 避免逐条往返。
     */
    @Insert("""
            <script>
            INSERT INTO t_episode_transcript_segment
                (subtitle_id, episode_id, drama_id, episode_no, start_ms, end_ms, text, status)
            VALUES
            <foreach collection="segments" item="segment" separator=",">
                (#{segment.subtitleId}, #{segment.episodeId}, #{segment.dramaId},
                 #{segment.episodeNo}, #{segment.startMs}, #{segment.endMs},
                 #{segment.text}, #{segment.status})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("segments") List<EpisodeTranscriptSegment> segments);

    /**
     * 查询某集可见字幕片段，按时间升序。
     */
    @Select("""
            SELECT s.id, s.subtitle_id, s.episode_id, s.drama_id, s.episode_no,
                   s.start_ms, s.end_ms, s.text, s.status
            FROM t_episode_transcript_segment s
            JOIN t_episode_subtitle sub ON sub.id = s.subtitle_id
            WHERE s.episode_id = #{episodeId}
              AND s.status = 1
              AND sub.status = 1
            ORDER BY s.start_ms, s.id
            """)
    List<EpisodeTranscriptSegment> selectVisibleByEpisodeId(@Param("episodeId") Long episodeId);
}
