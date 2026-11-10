package com.msd.drama.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/**
 * 互动计数 Mapper，只负责冗余计数的加减。
 * 递减统一带 count > 0 条件，避免 BIGINT UNSIGNED 在 0 时下溢报错。
 */
public interface DramaCounterMapper {

    /**
     * 短剧点赞数加一。
     */
    @Update("UPDATE t_drama SET like_count = like_count + 1 WHERE id = #{dramaId}")
    int incrementDramaLike(@Param("dramaId") Long dramaId);

    /**
     * 短剧点赞数减一。
     */
    @Update("UPDATE t_drama SET like_count = like_count - 1 WHERE id = #{dramaId} AND like_count > 0")
    int decrementDramaLike(@Param("dramaId") Long dramaId);

    /**
     * 短剧收藏数加一。
     */
    @Update("UPDATE t_drama SET favorite_count = favorite_count + 1 WHERE id = #{dramaId}")
    int incrementDramaFavorite(@Param("dramaId") Long dramaId);

    /**
     * 短剧收藏数减一。
     */
    @Update("UPDATE t_drama SET favorite_count = favorite_count - 1 "
            + "WHERE id = #{dramaId} AND favorite_count > 0")
    int decrementDramaFavorite(@Param("dramaId") Long dramaId);

    /**
     * 短剧评论数加一。
     */
    @Update("UPDATE t_drama SET comment_count = comment_count + 1 WHERE id = #{dramaId}")
    int incrementDramaComment(@Param("dramaId") Long dramaId);

    /**
     * 短剧评论数减一。
     */
    @Update("UPDATE t_drama SET comment_count = comment_count - 1 "
            + "WHERE id = #{dramaId} AND comment_count > 0")
    int decrementDramaComment(@Param("dramaId") Long dramaId);

    /**
     * 短剧浏览量加一。
     */
    @Update("UPDATE t_drama SET view_count = view_count + 1 WHERE id = #{dramaId}")
    int incrementDramaView(@Param("dramaId") Long dramaId);

    /**
     * 剧集点赞数加一。
     */
    @Update("UPDATE t_drama_episode SET like_count = like_count + 1 WHERE id = #{episodeId}")
    int incrementEpisodeLike(@Param("episodeId") Long episodeId);

    /**
     * 剧集点赞数减一。
     */
    @Update("UPDATE t_drama_episode SET like_count = like_count - 1 "
            + "WHERE id = #{episodeId} AND like_count > 0")
    int decrementEpisodeLike(@Param("episodeId") Long episodeId);

    /**
     * 剧集评论数加一。
     */
    @Update("UPDATE t_drama_episode SET comment_count = comment_count + 1 WHERE id = #{episodeId}")
    int incrementEpisodeComment(@Param("episodeId") Long episodeId);

    /**
     * 剧集评论数减一。
     */
    @Update("UPDATE t_drama_episode SET comment_count = comment_count - 1 "
            + "WHERE id = #{episodeId} AND comment_count > 0")
    int decrementEpisodeComment(@Param("episodeId") Long episodeId);

    /**
     * 剧集播放量加一。
     */
    @Update("UPDATE t_drama_episode SET play_count = play_count + 1 WHERE id = #{episodeId}")
    int incrementEpisodePlay(@Param("episodeId") Long episodeId);

    /**
     * 批量合并短剧点赞增量。
     */
    @Update("""
            <script>
            UPDATE t_drama
            SET like_count = GREATEST(CAST(like_count AS SIGNED) + CASE id
              <foreach collection="deltas" index="id" item="delta">
                WHEN #{id} THEN #{delta}
              </foreach>
              ELSE 0 END, 0)
            WHERE id IN (
              <foreach collection="deltas" index="id" item="delta" separator=",">#{id}</foreach>
            )
            </script>
            """)
    int batchDramaLike(@Param("deltas") Map<Long, Long> deltas);

    /**
     * 批量合并短剧收藏增量。
     */
    @Update("""
            <script>
            UPDATE t_drama
            SET favorite_count = GREATEST(CAST(favorite_count AS SIGNED) + CASE id
              <foreach collection="deltas" index="id" item="delta">
                WHEN #{id} THEN #{delta}
              </foreach>
              ELSE 0 END, 0)
            WHERE id IN (
              <foreach collection="deltas" index="id" item="delta" separator=",">#{id}</foreach>
            )
            </script>
            """)
    int batchDramaFavorite(@Param("deltas") Map<Long, Long> deltas);

    /**
     * 批量合并短剧评论增量。
     */
    @Update("""
            <script>
            UPDATE t_drama
            SET comment_count = GREATEST(CAST(comment_count AS SIGNED) + CASE id
              <foreach collection="deltas" index="id" item="delta">
                WHEN #{id} THEN #{delta}
              </foreach>
              ELSE 0 END, 0)
            WHERE id IN (
              <foreach collection="deltas" index="id" item="delta" separator=",">#{id}</foreach>
            )
            </script>
            """)
    int batchDramaComment(@Param("deltas") Map<Long, Long> deltas);

    /**
     * 批量合并短剧浏览量增量。
     */
    @Update("""
            <script>
            UPDATE t_drama
            SET view_count = GREATEST(CAST(view_count AS SIGNED) + CASE id
              <foreach collection="deltas" index="id" item="delta">
                WHEN #{id} THEN #{delta}
              </foreach>
              ELSE 0 END, 0)
            WHERE id IN (
              <foreach collection="deltas" index="id" item="delta" separator=",">#{id}</foreach>
            )
            </script>
            """)
    int batchDramaView(@Param("deltas") Map<Long, Long> deltas);

    /**
     * 批量合并剧集点赞增量。
     */
    @Update("""
            <script>
            UPDATE t_drama_episode
            SET like_count = GREATEST(CAST(like_count AS SIGNED) + CASE id
              <foreach collection="deltas" index="id" item="delta">
                WHEN #{id} THEN #{delta}
              </foreach>
              ELSE 0 END, 0)
            WHERE id IN (
              <foreach collection="deltas" index="id" item="delta" separator=",">#{id}</foreach>
            )
            </script>
            """)
    int batchEpisodeLike(@Param("deltas") Map<Long, Long> deltas);

    /**
     * 批量合并剧集评论增量。
     */
    @Update("""
            <script>
            UPDATE t_drama_episode
            SET comment_count = GREATEST(CAST(comment_count AS SIGNED) + CASE id
              <foreach collection="deltas" index="id" item="delta">
                WHEN #{id} THEN #{delta}
              </foreach>
              ELSE 0 END, 0)
            WHERE id IN (
              <foreach collection="deltas" index="id" item="delta" separator=",">#{id}</foreach>
            )
            </script>
            """)
    int batchEpisodeComment(@Param("deltas") Map<Long, Long> deltas);

    /**
     * 批量合并剧集播放量增量。
     */
    @Update("""
            <script>
            UPDATE t_drama_episode
            SET play_count = GREATEST(CAST(play_count AS SIGNED) + CASE id
              <foreach collection="deltas" index="id" item="delta">
                WHEN #{id} THEN #{delta}
              </foreach>
              ELSE 0 END, 0)
            WHERE id IN (
              <foreach collection="deltas" index="id" item="delta" separator=",">#{id}</foreach>
            )
            </script>
            """)
    int batchEpisodePlay(@Param("deltas") Map<Long, Long> deltas);
}
