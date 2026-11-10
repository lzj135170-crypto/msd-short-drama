package com.msd.drama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.drama.entity.EpisodeSubtitle;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分集字幕 Mapper。
 */
public interface EpisodeSubtitleMapper extends BaseMapper<EpisodeSubtitle> {

    /**
     * 同一集同一语言保留一份字幕，重复导入覆盖旧记录。
     */
    @Insert("""
            INSERT INTO t_episode_subtitle
                (episode_id, language, format, object_key, source, status, segment_count)
            VALUES
                (#{episodeId}, #{language}, #{format}, #{objectKey}, #{source}, 1, #{segmentCount})
            ON DUPLICATE KEY UPDATE
                format = VALUES(format),
                object_key = VALUES(object_key),
                source = VALUES(source),
                status = 1,
                segment_count = VALUES(segment_count)
            """)
    int upsert(@Param("episodeId") Long episodeId,
               @Param("language") String language,
               @Param("format") String format,
               @Param("objectKey") String objectKey,
               @Param("source") String source,
               @Param("segmentCount") int segmentCount);

    /**
     * 查询字幕主键，供片段写入和替换。
     */
    @Select("""
            SELECT id FROM t_episode_subtitle
            WHERE episode_id = #{episodeId} AND language = #{language}
            """)
    Long selectIdByEpisodeAndLanguage(@Param("episodeId") Long episodeId,
                                      @Param("language") String language);
}
