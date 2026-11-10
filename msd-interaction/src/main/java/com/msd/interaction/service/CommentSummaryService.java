package com.msd.interaction.service;

import com.msd.common.result.Result;
import com.msd.interaction.client.CommentSummaryClient;
import com.msd.interaction.dto.CommentSummaryClientRequest;
import com.msd.interaction.dto.CommentSummaryClientResponse;
import com.msd.interaction.dto.CommentSummaryView;
import com.msd.interaction.entity.Comment;
import com.msd.interaction.mapper.CommentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评论课代表编排：聚合高赞评论，调用 content 生成摘要并缓存。
 *
 * <p>评论只作为观点数据，不当作剧情事实；剧透/广告评论不进入摘要。</p>
 */
@Service
public class CommentSummaryService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(CommentSummaryService.class);
    /** 最多提交评论数。 */
    private static final int MAX_COMMENTS = 50;
    /** 代表评论兜底数。 */
    private static final int FALLBACK_REPRESENTATIVES = 2;
    /** 降级标记：没有可总结评论。 */
    public static final String FLAG_EMPTY = "comment_summary_empty";
    /** 降级标记：下游不可用。 */
    public static final String FLAG_UNAVAILABLE = "comment_summary_unavailable";
    /** 明显剧透/广告词，命中后不进入摘要。 */
    private static final Set<String> SPOILER_TERMS = Set.of(
            "大结局", "结局", "凶手是", "真凶", "剧透", "最后一集", "刷单", "加微信", "私下转账");

    // 评论 Mapper
    private final CommentMapper commentMapper;
    // content 摘要客户端
    private final CommentSummaryClient summaryClient;
    // 本地缓存
    private final CommentSummaryCache summaryCache;

    /**
     * 注入 Mapper、客户端和缓存。
     */
    public CommentSummaryService(CommentMapper commentMapper,
                                 CommentSummaryClient summaryClient,
                                 CommentSummaryCache summaryCache) {
        this.commentMapper = commentMapper;
        this.summaryClient = summaryClient;
        this.summaryCache = summaryCache;
    }

    /**
     * 查询评论课代表；失败返回降级空结果。
     */
    public CommentSummaryView getSummary(Long dramaId, Long episodeId) {
        // 1. 参数和缓存
        if (dramaId == null || dramaId <= 0) {
            return degraded(FLAG_EMPTY, 0);
        }
        CommentSummaryView cached = summaryCache.get(dramaId, episodeId);
        if (cached != null) {
            return cached;
        }

        // 2. 取高赞根评论，过滤剧透/广告
        List<Comment> comments;
        try {
            comments = commentMapper.selectTopComments(dramaId, episodeId, MAX_COMMENTS);
        } catch (Exception e) {
            log.warn("load comment summary candidates failed: dramaId={}, episodeId={}",
                    dramaId, episodeId, e);
            return degraded(FLAG_UNAVAILABLE, 0);
        }
        List<Comment> eligible = new ArrayList<>();
        if (comments != null) {
            for (Comment comment : comments) {
                if (comment != null && comment.getContent() != null
                        && !isSpoilerOrAd(comment.getContent())) {
                    eligible.add(comment);
                }
            }
        }
        if (eligible.isEmpty()) {
            return degraded(FLAG_EMPTY, 0);
        }

        // 3. 调用 content 生成摘要
        long startedAt = System.currentTimeMillis();
        CommentSummaryClientRequest request = new CommentSummaryClientRequest(
                dramaId, episodeId,
                eligible.stream()
                        .map(item -> new CommentSummaryClientRequest.CommentInput(
                                item.getId(), item.getContent(), item.getLikeCount()))
                        .toList());
        CommentSummaryClientResponse response;
        try {
            Result<CommentSummaryClientResponse> result = summaryClient.summarize(request);
            if (result == null || !result.isSuccess() || result.getData() == null) {
                return degraded(FLAG_UNAVAILABLE, System.currentTimeMillis() - startedAt);
            }
            response = result.getData();
        } catch (Exception e) {
            log.warn("comment summary downstream failed: dramaId={}, episodeId={}",
                    dramaId, episodeId, e);
            return degraded(FLAG_UNAVAILABLE, System.currentTimeMillis() - startedAt);
        }
        if (response.degraded() || response.summary().isBlank()) {
            return new CommentSummaryView("", response.sentiment(), List.of(), true,
                    response.degradedFlags().isEmpty()
                            ? List.of(FLAG_UNAVAILABLE) : response.degradedFlags(),
                    response.elapsedMs());
        }

        // 4. 只返回真实存在的代表评论
        Map<Long, Comment> byId = new LinkedHashMap<>();
        eligible.forEach(item -> byId.put(item.getId(), item));
        List<CommentSummaryView.RepresentativeComment> representatives = new ArrayList<>();
        for (Long commentId : response.representativeCommentIds()) {
            Comment comment = byId.get(commentId);
            if (comment != null && representatives.size() < 3) {
                representatives.add(new CommentSummaryView.RepresentativeComment(
                        comment.getId(), comment.getContent(), comment.getLikeCount()));
            }
        }
        if (representatives.isEmpty()) {
            for (Comment comment : eligible) {
                representatives.add(new CommentSummaryView.RepresentativeComment(
                        comment.getId(), comment.getContent(), comment.getLikeCount()));
                if (representatives.size() >= FALLBACK_REPRESENTATIVES) {
                    break;
                }
            }
        }

        CommentSummaryView view = new CommentSummaryView(
                response.summary(), response.sentiment(), representatives, false, List.of(),
                response.elapsedMs());
        summaryCache.put(dramaId, episodeId, view);
        return view;
    }

    /**
     * 简单过滤剧透和明显广告。
     */
    private boolean isSpoilerOrAd(String content) {
        for (String term : SPOILER_TERMS) {
            if (content.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造降级视图。
     */
    private CommentSummaryView degraded(String flag, long elapsedMs) {
        return new CommentSummaryView("", "neutral", List.of(), true,
                List.of(flag), Math.max(elapsedMs, 0));
    }
}
