package com.msd.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.interaction.entity.Comment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 评论 Mapper。
 */
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 按用户和客户端请求幂等键查询已创建的评论。
     */
    @Select("SELECT id, drama_id, episode_id, user_id, root_id, parent_id, reply_to_user_id, "
            + "content, client_request_id, like_count, reply_count, status, create_time, update_time "
            + "FROM t_comment WHERE user_id = #{userId} AND client_request_id = #{clientRequestId}")
    Comment selectByClientRequestId(@Param("userId") Long userId,
                                    @Param("clientRequestId") String clientRequestId);

    /**
     * 回复数加一，只更新可见的根评论。
     */
    @Update("UPDATE t_comment SET reply_count = reply_count + 1 "
            + "WHERE id = #{rootId} AND status = 1")
    int incrementReplyCount(@Param("rootId") Long rootId);

    /**
     * 回复数减一，防止无符号下溢。
     */
    @Update("UPDATE t_comment SET reply_count = reply_count - 1 "
            + "WHERE id = #{rootId} AND reply_count > 0")
    int decrementReplyCount(@Param("rootId") Long rootId);

    /**
     * 评论点赞数加一，只允许点赞可见评论。
     */
    @Update("UPDATE t_comment SET like_count = like_count + 1 "
            + "WHERE id = #{commentId} AND status = 1")
    int incrementLikeCount(@Param("commentId") Long commentId);

    /**
     * 评论点赞数减一，防止无符号下溢。
     */
    @Update("UPDATE t_comment SET like_count = like_count - 1 "
            + "WHERE id = #{commentId} AND like_count > 0")
    int decrementLikeCount(@Param("commentId") Long commentId);

    /**
     * 逻辑删除评论，只有作者且当前可见时生效。
     */
    @Update("UPDATE t_comment SET status = 3 "
            + "WHERE id = #{commentId} AND user_id = #{userId} AND status = 1")
    int softDelete(@Param("commentId") Long commentId, @Param("userId") Long userId);

    /**
     * 按 ID 逻辑删除评论，用于级联删除根评论下的回复。
     */
    @Update("UPDATE t_comment SET status = 3 WHERE id = #{commentId} AND status = 1")
    int softDeleteById(@Param("commentId") Long commentId);

    /**
     * 查询短剧级或剧集级的根评论，按时间倒序。
     */
    @Select("""
            <script>
            SELECT id, drama_id, episode_id, user_id, root_id, parent_id, reply_to_user_id,
                   content, like_count, reply_count, status, create_time, update_time
            FROM t_comment
            WHERE drama_id = #{dramaId} AND status = 1 AND root_id = 0
            <choose>
                <when test="episodeId != null">AND episode_id = #{episodeId}</when>
                <otherwise>AND episode_id IS NULL</otherwise>
            </choose>
            ORDER BY create_time DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Comment> selectRootComments(@Param("dramaId") Long dramaId,
                                     @Param("episodeId") Long episodeId,
                                     @Param("offset") long offset,
                                     @Param("pageSize") int pageSize);

    /**
     * 统计短剧级或剧集级的根评论数。
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM t_comment
            WHERE drama_id = #{dramaId} AND status = 1 AND root_id = 0
            <choose>
                <when test="episodeId != null">AND episode_id = #{episodeId}</when>
                <otherwise>AND episode_id IS NULL</otherwise>
            </choose>
            </script>
            """)
    long countRootComments(@Param("dramaId") Long dramaId,
                           @Param("episodeId") Long episodeId);

    /**
     * 查询某个根评论下的回复，按时间正序。
     */
    @Select("SELECT id, drama_id, episode_id, user_id, root_id, parent_id, reply_to_user_id, "
            + "content, like_count, reply_count, status, create_time, update_time "
            + "FROM t_comment WHERE root_id = #{rootId} AND status = 1 "
            + "ORDER BY create_time ASC, id ASC LIMIT #{offset}, #{pageSize}")
    List<Comment> selectReplies(@Param("rootId") Long rootId,
                                @Param("offset") long offset,
                                @Param("pageSize") int pageSize);

    /**
     * 统计某个根评论下的回复数。
     */
    @Select("SELECT COUNT(*) FROM t_comment WHERE root_id = #{rootId} AND status = 1")
    long countReplies(@Param("rootId") Long rootId);

    /**
     * 查询某个根评论下所有可见回复，用于级联删除。
     */
    @Select("SELECT id, drama_id, episode_id, user_id, root_id, parent_id, reply_to_user_id, "
            + "content, like_count, reply_count, status, create_time, update_time "
            + "FROM t_comment WHERE root_id = #{rootId} AND status = 1 ORDER BY id")
    List<Comment> selectVisibleReplies(@Param("rootId") Long rootId);

    /**
     * 查询我的评论，排除已删除状态。
     */
    @Select("SELECT id, drama_id, episode_id, user_id, root_id, parent_id, reply_to_user_id, "
            + "content, like_count, reply_count, status, create_time, update_time "
            + "FROM t_comment WHERE user_id = #{userId} AND status <> 3 "
            + "ORDER BY create_time DESC, id DESC LIMIT #{offset}, #{pageSize}")
    List<Comment> selectMyComments(@Param("userId") Long userId,
                                   @Param("offset") long offset,
                                   @Param("pageSize") int pageSize);

    /**
     * 统计我的评论数，排除已删除状态。
     */
    @Select("SELECT COUNT(*) FROM t_comment WHERE user_id = #{userId} AND status <> 3")
    long countMyComments(@Param("userId") Long userId);

    /**
     * 查询评论课代表候选：可见根评论，按点赞数倒序。
     */
    @Select("""
            <script>
            SELECT id, drama_id, episode_id, user_id, root_id, parent_id, reply_to_user_id,
                   content, like_count, reply_count, status, create_time, update_time
            FROM t_comment
            WHERE drama_id = #{dramaId} AND status = 1 AND root_id = 0
            <choose>
                <when test="episodeId != null">AND episode_id = #{episodeId}</when>
                <otherwise>AND episode_id IS NULL</otherwise>
            </choose>
            ORDER BY like_count DESC, create_time DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Comment> selectTopComments(@Param("dramaId") Long dramaId,
                                    @Param("episodeId") Long episodeId,
                                    @Param("limit") int limit);

    /**
     * 管理员分页查询评论，可按状态和短剧过滤。
     */
    @Select("""
            <script>
            SELECT id, drama_id, episode_id, user_id, root_id, parent_id, reply_to_user_id,
                   content, like_count, reply_count, status,
                   ai_review_status, ai_review_result, ai_review_time, create_time, update_time
            FROM t_comment
            WHERE 1 = 1
            <if test="status != null">AND status = #{status}</if>
            <if test="dramaId != null">AND drama_id = #{dramaId}</if>
            <if test="aiReviewStatus != null">AND ai_review_status = #{aiReviewStatus}</if>
            ORDER BY create_time DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<Comment> selectAdminPage(@Param("status") Integer status,
                                  @Param("dramaId") Long dramaId,
                                  @Param("aiReviewStatus") Integer aiReviewStatus,
                                  @Param("offset") long offset,
                                  @Param("pageSize") int pageSize);

    /**
     * 管理员评论列表总数，过滤条件与分页查询一致。
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM t_comment
            WHERE 1 = 1
            <if test="status != null">AND status = #{status}</if>
            <if test="dramaId != null">AND drama_id = #{dramaId}</if>
            <if test="aiReviewStatus != null">AND ai_review_status = #{aiReviewStatus}</if>
            </script>
            """)
    long countAdminPage(@Param("status") Integer status,
                        @Param("dramaId") Long dramaId,
                        @Param("aiReviewStatus") Integer aiReviewStatus);

    /**
     * 标记 AI 审核中；只改 AI 列。
     */
    @Update("""
            UPDATE t_comment
            SET ai_review_status = 1
            WHERE id = #{commentId} AND status <> 3
            """)
    int markAiReviewing(@Param("commentId") Long commentId);

    /**
     * 写回 AI 预审结果；只改 AI 列，绝不改人工 status。
     */
    @Update("""
            UPDATE t_comment
            SET ai_review_status = #{status}, ai_review_result = #{result},
                ai_review_time = NOW(3)
            WHERE id = #{commentId} AND status <> 3
            """)
    int updateAiReview(@Param("commentId") Long commentId,
                       @Param("status") Integer status,
                       @Param("result") String result);

    /**
     * 查询根评论下未删除的回复，用于隐藏/删除级联。
     */
    @Select("SELECT id, drama_id, episode_id, user_id, root_id, parent_id, reply_to_user_id, "
            + "content, like_count, reply_count, status, create_time, update_time "
            + "FROM t_comment WHERE root_id = #{rootId} AND status IN (1, 2) ORDER BY id")
    List<Comment> selectActiveReplies(@Param("rootId") Long rootId);

    /**
     * 管理员隐藏评论：待审或可见 -> 隐藏。
     */
    @Update("UPDATE t_comment SET status = 2 WHERE id = #{commentId} AND status IN (0, 1)")
    int adminHide(@Param("commentId") Long commentId);

    /**
     * 管理员删除评论：待审、可见或隐藏 -> 已删除。
     */
    @Update("UPDATE t_comment SET status = 3 WHERE id = #{commentId} AND status IN (0, 1, 2)")
    int adminDeleteById(@Param("commentId") Long commentId);
}
