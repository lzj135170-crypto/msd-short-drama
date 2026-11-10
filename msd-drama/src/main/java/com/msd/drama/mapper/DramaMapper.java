package com.msd.drama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.msd.drama.dto.DramaReviewCandidateRow;
import com.msd.drama.entity.Drama;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 短剧 Mapper。
 */
public interface DramaMapper extends BaseMapper<Drama> {

    /**
     * 查询热播短剧：只取已上架数据，按加权热度降序。
     */
    @Select("""
            SELECT id, title, subtitle, cover_url, description, category_id, author_id, actors,
                   episode_count, review_status, publish_time,
                   view_count, like_count, favorite_count, comment_count
            FROM t_drama
            WHERE deleted = 0 AND review_status = 2 AND publish_time IS NOT NULL
            ORDER BY (view_count + like_count * 3 + favorite_count * 5 + comment_count * 2) DESC,
                     publish_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<Drama> selectHot(@Param("limit") int limit);

    /**
     * 查询最新上架短剧，按上架时间降序。
     */
    @Select("""
            SELECT id, title, subtitle, cover_url, description, category_id, author_id, actors,
                   episode_count, review_status, publish_time,
                   view_count, like_count, favorite_count, comment_count
            FROM t_drama
            WHERE deleted = 0 AND review_status = 2 AND publish_time IS NOT NULL
            ORDER BY publish_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<Drama> selectLatest(@Param("limit") int limit);

    /**
     * 按 ID 查询已上架短剧，未上架或已删除返回 null。
     */
    @Select("""
            SELECT id, title, subtitle, cover_url, description, category_id, author_id, actors,
                   episode_count, review_status, publish_time,
                   view_count, like_count, favorite_count, comment_count
            FROM t_drama
            WHERE id = #{dramaId}
              AND deleted = 0
              AND review_status = 2
              AND publish_time IS NOT NULL
            """)
    Drama selectPublicById(@Param("dramaId") Long dramaId);

    /**
     * 按 ID 批量查询已上架短剧，供收藏/历史/点赞列表补齐。
     */
    @Select("""
            <script>
            SELECT id, title, subtitle, cover_url, category_id, episode_count
            FROM t_drama
            WHERE deleted = 0
              AND review_status = 2
              AND publish_time IS NOT NULL
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Drama> selectPublicByIds(@Param("ids") List<Long> ids);

    /**
     * 分页查询 RAG 索引需要的已上架短剧，按 ID 升序保证分页稳定。
     */
    @Select("""
            SELECT id, title, subtitle, cover_url, description, category_id, actors,
                   episode_count, publish_time,
                   view_count, like_count, favorite_count, comment_count
            FROM t_drama
            WHERE deleted = 0 AND review_status = 2 AND publish_time IS NOT NULL
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Drama> selectRagExport(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 统计 RAG 索引可导出的已上架短剧数量。
     */
    @Select("""
            SELECT COUNT(*)
            FROM t_drama
            WHERE deleted = 0 AND review_status = 2 AND publish_time IS NOT NULL
            """)
    long countRagExport();

    /**
     * 查询相似短剧的规则兜底候选，按加权热度降序。
     *
     * <p>候选池故意大于最终条数，由服务层按同分类、共同标签重排。</p>
     */
    @Select("""
            SELECT id, title, subtitle, cover_url, description, category_id, author_id, actors,
                   episode_count, publish_time, view_count, like_count, favorite_count, comment_count
            FROM t_drama
            WHERE deleted = 0
              AND review_status = 2
              AND publish_time IS NOT NULL
              AND id <> #{excludeId}
            ORDER BY (view_count + like_count * 3 + favorite_count * 5 + comment_count * 2) DESC,
                     publish_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<Drama> selectRelatedCandidates(@Param("excludeId") Long excludeId,
                                        @Param("limit") int limit);

    /**
     * 统计已上架短剧数量，供布隆过滤器估算容量。
     */
    @Select("""
            SELECT COUNT(*)
            FROM t_drama
            WHERE deleted = 0 AND review_status = 2 AND publish_time IS NOT NULL
            """)
    long countPublic();

    /**
     * 分页查询已上架短剧 ID，供布隆过滤器重建。
     */
    @Select("""
            SELECT id
            FROM t_drama
            WHERE deleted = 0 AND review_status = 2 AND publish_time IS NOT NULL
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Long> selectPublicIds(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 搜索已上架短剧：关键词匹配标题、副标题、简介和演员，可按分类过滤。
     *
     * <p>关键词由服务层转义 LIKE 通配符，SQL 固定使用 `/` 作为转义符；
     * `hot=true` 复用首页加权热度公式，否则按最新上架排序。</p>
     */
    @Select("""
            <script>
            SELECT id, title, subtitle, cover_url, description, category_id, author_id, actors,
                   episode_count, review_status, publish_time,
                   view_count, like_count, favorite_count, comment_count
            FROM t_drama
            WHERE deleted = 0 AND review_status = 2 AND publish_time IS NOT NULL
            <if test="keyword != null and keyword != ''">
                AND (title LIKE CONCAT('%', #{keyword}, '%') ESCAPE '/'
                     OR subtitle LIKE CONCAT('%', #{keyword}, '%') ESCAPE '/'
                     OR description LIKE CONCAT('%', #{keyword}, '%') ESCAPE '/'
                     OR actors LIKE CONCAT('%', #{keyword}, '%') ESCAPE '/')
            </if>
            <if test="categoryId != null">
                AND category_id = #{categoryId}
            </if>
            <choose>
                <when test="hot">
                    ORDER BY (view_count + like_count * 3 + favorite_count * 5 + comment_count * 2) DESC,
                             publish_time DESC, id DESC
                </when>
                <otherwise>
                    ORDER BY publish_time DESC, id DESC
                </otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Drama> searchPublic(@Param("keyword") String keyword,
                             @Param("categoryId") Long categoryId,
                             @Param("hot") boolean hot,
                             @Param("offset") long offset,
                             @Param("limit") int limit);

    /**
     * 统计搜索命中的已上架短剧数量，过滤条件与 {@link #searchPublic} 一致。
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM t_drama
            WHERE deleted = 0 AND review_status = 2 AND publish_time IS NOT NULL
            <if test="keyword != null and keyword != ''">
                AND (title LIKE CONCAT('%', #{keyword}, '%') ESCAPE '/'
                     OR subtitle LIKE CONCAT('%', #{keyword}, '%') ESCAPE '/'
                     OR description LIKE CONCAT('%', #{keyword}, '%') ESCAPE '/'
                     OR actors LIKE CONCAT('%', #{keyword}, '%') ESCAPE '/')
            </if>
            <if test="categoryId != null">
                AND category_id = #{categoryId}
            </if>
            </script>
            """)
    long countSearchPublic(@Param("keyword") String keyword,
                           @Param("categoryId") Long categoryId);

    /**
     * 查询待 AI 审核的短剧，按提交时间升序。
     *
     * @param includeFailed true 时同时返回 AI 失败（ai_review_status=4）的短剧
     */
    @Select("""
            <script>
            SELECT id AS drama_id, title, subtitle, description, cover_url, actors,
                   author_id, category_id, episode_count, review_status, ai_review_status,
                   ai_review_result, submit_time, ai_review_time, review_time, publish_time
            FROM t_drama
            WHERE deleted = 0
              AND review_status = 1
              AND (ai_review_status IN (0, 1) OR ai_review_status IS NULL
              <if test="includeFailed">
                OR ai_review_status = 4
              </if>
              )
            ORDER BY submit_time ASC, id ASC
            LIMIT #{limit}
            </script>
            """)
    List<DramaReviewCandidateRow> selectAiReviewCandidates(
            @Param("limit") int limit,
            @Param("includeFailed") boolean includeFailed);

    /**
     * 按 ID 查询审核详情，不限制人工审核状态。
     */
    @Select("""
            SELECT id AS drama_id, title, subtitle, description, cover_url, actors,
                   author_id, category_id, episode_count, review_status, ai_review_status,
                   ai_review_result, submit_time, ai_review_time, review_time, publish_time
            FROM t_drama
            WHERE id = #{dramaId} AND deleted = 0
            """)
    DramaReviewCandidateRow selectReviewById(@Param("dramaId") Long dramaId);

    /**
     * 分页查询审核队列，可按人工审核状态和 AI 审核状态过滤。
     */
    @Select("""
            <script>
            SELECT id AS drama_id, title, subtitle, description, cover_url, actors,
                   author_id, category_id, episode_count, review_status, ai_review_status,
                   ai_review_result, submit_time, ai_review_time, review_time, publish_time
            FROM t_drama
            WHERE deleted = 0
            <if test="reviewStatus != null">AND review_status = #{reviewStatus}</if>
            <if test="aiReviewStatus != null">AND ai_review_status = #{aiReviewStatus}</if>
            ORDER BY submit_time DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DramaReviewCandidateRow> selectReviewPage(
            @Param("reviewStatus") Integer reviewStatus,
            @Param("aiReviewStatus") Integer aiReviewStatus,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 统计审核队列数量。
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM t_drama
            WHERE deleted = 0
            <if test="reviewStatus != null">AND review_status = #{reviewStatus}</if>
            <if test="aiReviewStatus != null">AND ai_review_status = #{aiReviewStatus}</if>
            </script>
            """)
    long countReviews(@Param("reviewStatus") Integer reviewStatus,
                      @Param("aiReviewStatus") Integer aiReviewStatus);

    /**
     * 提交审核：草稿或驳回可进入待审，同时重置 AI 结果。
     *
     * @return 影响行数，1 表示状态迁移成功
     */
    @Update("""
            UPDATE t_drama
            SET review_status = 1, submit_time = NOW(3),
                reviewer_id = NULL, review_time = NULL, review_remark = '',
                ai_review_status = 1, ai_review_result = NULL, ai_review_time = NULL
            WHERE id = #{dramaId} AND deleted = 0 AND review_status IN (0, 3)
            """)
    int submitReview(@Param("dramaId") Long dramaId);

    /**
     * 人工审核通过：待审进入已上架。
     */
    @Update("""
            UPDATE t_drama
            SET review_status = 2, reviewer_id = #{reviewerId}, review_time = NOW(3),
                publish_time = IFNULL(publish_time, NOW(3)),
                review_remark = #{remark}, offline_time = NULL
            WHERE id = #{dramaId} AND deleted = 0 AND review_status = 1
            """)
    int approveReview(@Param("dramaId") Long dramaId,
                      @Param("reviewerId") Long reviewerId,
                      @Param("remark") String remark);

    /**
     * 人工审核驳回：待审进入驳回。
     */
    @Update("""
            UPDATE t_drama
            SET review_status = 3, reviewer_id = #{reviewerId}, review_time = NOW(3),
                review_remark = #{remark}
            WHERE id = #{dramaId} AND deleted = 0 AND review_status = 1
            """)
    int rejectReview(@Param("dramaId") Long dramaId,
                     @Param("reviewerId") Long reviewerId,
                     @Param("remark") String remark);

    /**
     * 下架：已上架进入下架。
     */
    @Update("""
            UPDATE t_drama
            SET review_status = 4, reviewer_id = #{reviewerId}, review_time = NOW(3),
                offline_time = NOW(3), review_remark = #{remark}
            WHERE id = #{dramaId} AND deleted = 0 AND review_status = 2
            """)
    int offlineReview(@Param("dramaId") Long dramaId,
                      @Param("reviewerId") Long reviewerId,
                      @Param("remark") String remark);

    /**
     * 写回 AI 审核结果；只在待审状态生效。
     */
    @Update("""
            UPDATE t_drama
            SET ai_review_status = #{status}, ai_review_result = #{json},
                ai_review_time = NOW(3)
            WHERE id = #{dramaId} AND deleted = 0 AND review_status = 1
            """)
    int writeAiReviewResult(@Param("dramaId") Long dramaId,
                            @Param("status") Integer status,
                            @Param("json") String json);

    /**
     * 分页查询创作者自己的短剧。
     */
    @Select("""
            SELECT id, title, subtitle, cover_url, description, category_id, author_id, actors,
                   episode_count, review_status, reviewer_id, review_remark, submit_time,
                   review_time, publish_time, offline_time, ai_review_status,
                   ai_review_result, ai_review_time, view_count, like_count,
                   favorite_count, comment_count, deleted
            FROM t_drama
            WHERE deleted = 0 AND author_id = #{authorId}
            ORDER BY update_time DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Drama> selectByAuthor(@Param("authorId") Long authorId,
                               @Param("offset") int offset,
                               @Param("limit") int limit);

    /**
     * 统计创作者自己的短剧数量。
     */
    @Select("SELECT COUNT(*) FROM t_drama WHERE deleted = 0 AND author_id = #{authorId}")
    long countByAuthor(@Param("authorId") Long authorId);

    /**
     * 软删除草稿或驳回的短剧。
     *
     * @return 影响行数，1 表示删除成功
     */
    @Update("""
            UPDATE t_drama SET deleted = 1
            WHERE id = #{dramaId} AND deleted = 0 AND review_status IN (0, 3)
            """)
    int softDeleteDraft(@Param("dramaId") Long dramaId);
}
