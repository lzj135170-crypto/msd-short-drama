package com.msd.interaction.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.msd.common.dto.DramaBriefDTO;
import com.msd.common.dto.EpisodeBriefDTO;
import com.msd.common.dto.IdBatchRequest;
import com.msd.common.dto.PageQuery;
import com.msd.common.dto.UserInfoDTO;
import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.interaction.client.DramaClient;
import com.msd.interaction.client.UserClient;
import com.msd.interaction.dto.CommentView;
import com.msd.interaction.dto.DramaState;
import com.msd.interaction.dto.FavoriteItem;
import com.msd.interaction.dto.HistoryItem;
import com.msd.interaction.dto.InteractionStats;
import com.msd.interaction.dto.InteractionStatsRow;
import com.msd.interaction.dto.LikedDramaItem;
import com.msd.interaction.dto.MyCommentView;
import com.msd.interaction.entity.Comment;
import com.msd.interaction.entity.UserFavorite;
import com.msd.interaction.entity.UserLike;
import com.msd.interaction.entity.WatchHistory;
import com.msd.interaction.mapper.CommentMapper;
import com.msd.interaction.mapper.InteractionStatsMapper;
import com.msd.interaction.mapper.UserFavoriteMapper;
import com.msd.interaction.mapper.UserLikeMapper;
import com.msd.interaction.mapper.WatchHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 互动列表与个人中心查询。
 *
 * <p>列表数据由 user/drama 的批量接口补齐；补齐失败时降级为 null，不影响列表本身。</p>
 */
@Service
public class InteractionFeedService {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(InteractionFeedService.class);
    /** 批量接口单次最大 ID 数。 */
    private static final int BATCH_SIZE = 100;
    /** 点赞目标类型：短剧。 */
    private static final int TARGET_DRAMA = 1;
    /** 点赞目标类型：评论。 */
    private static final int TARGET_COMMENT = 3;

    // 评论 Mapper
    private final CommentMapper commentMapper;
    // 收藏 Mapper
    private final UserFavoriteMapper favoriteMapper;
    // 观看历史 Mapper
    private final WatchHistoryMapper historyMapper;
    // 点赞 Mapper
    private final UserLikeMapper likeMapper;
    // 个人中心四项计数聚合 Mapper
    private final InteractionStatsMapper statsMapper;
    // 评论首页短期缓存
    private final CommentCache commentCache;
    // 用户服务 Feign 客户端，用于批量补齐作者信息
    private final UserClient userClient;
    // 短剧服务 Feign 客户端，用于批量补齐短剧和剧集信息
    private final DramaClient dramaClient;

    /**
     * 注入互动 Mapper、计数聚合、评论缓存和批量补齐客户端。
     */
    public InteractionFeedService(CommentMapper commentMapper,
                                  UserFavoriteMapper favoriteMapper,
                                  WatchHistoryMapper historyMapper,
                                  UserLikeMapper likeMapper,
                                  InteractionStatsMapper statsMapper,
                                  CommentCache commentCache,
                                  UserClient userClient,
                                  DramaClient dramaClient) {
        this.commentMapper = commentMapper;
        this.favoriteMapper = favoriteMapper;
        this.historyMapper = historyMapper;
        this.likeMapper = likeMapper;
        this.statsMapper = statsMapper;
        this.commentCache = commentCache;
        this.userClient = userClient;
        this.dramaClient = dramaClient;
    }

    /**
     * 分页查询根评论。
     */
    public PageResult<CommentView> listComments(Long userId, Long dramaId, Long episodeId, PageQuery page) {
        // 1. 先读 15 秒本地缓存，只缓存原始评论行
        CommentCache.PageData cached = commentCache.get(
                dramaId, episodeId, page.pageNo(), page.pageSize());
        if (cached != null) {
            return PageResult.of(page.pageNo(), page.pageSize(), cached.total(),
                    toCommentViews(userId, cached.comments()));
        }

        // 2. 未命中时统计总数并查询当前页
        long total = commentMapper.countRootComments(dramaId, episodeId);
        List<Comment> comments = total == 0
                ? List.of()
                : commentMapper.selectRootComments(dramaId, episodeId, page.offset(), page.pageSize());
        commentCache.put(dramaId, episodeId, page.pageNo(), page.pageSize(),
                new CommentCache.PageData(total, comments));

        // 3. 合并当前用户点赞状态后返回
        return PageResult.of(page.pageNo(), page.pageSize(), total, toCommentViews(userId, comments));
    }

    /**
     * 分页查询某个根评论下的回复。
     */
    public PageResult<CommentView> listReplies(Long userId, Long rootId, PageQuery page) {
        // 1. 根评论必须存在且可见
        Comment root = commentMapper.selectById(rootId);
        if (root == null
                || !Integer.valueOf(1).equals(root.getStatus())
                || root.getRootId() == null
                || root.getRootId() != 0L) {
            throw new BizException(ResultCode.NOT_FOUND, "根评论不存在");
        }

        // 2. 统计并查询回复
        long total = commentMapper.countReplies(rootId);
        List<Comment> replies = total == 0
                ? List.of()
                : commentMapper.selectReplies(rootId, page.offset(), page.pageSize());

        // 3. 补齐作者和点赞状态
        return PageResult.of(page.pageNo(), page.pageSize(), total, toCommentViews(userId, replies));
    }

    /**
     * 分页查询我的评论。
     */
    public PageResult<MyCommentView> listMyComments(Long userId, PageQuery page) {
        // 1. 统计并查询当前页
        long total = commentMapper.countMyComments(userId);
        List<Comment> comments = total == 0
                ? List.of()
                : commentMapper.selectMyComments(userId, page.offset(), page.pageSize());

        // 2. 批量补齐短剧和剧集标题
        Map<Long, DramaBriefDTO> dramas = loadDramas(comments.stream()
                .map(Comment::getDramaId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, EpisodeBriefDTO> episodes = loadEpisodes(comments.stream()
                .map(Comment::getEpisodeId).filter(Objects::nonNull).collect(Collectors.toSet()));

        // 3. 映射为列表项
        List<MyCommentView> records = comments.stream()
                .map(comment -> {
                    DramaBriefDTO drama = dramas.get(comment.getDramaId());
                    EpisodeBriefDTO episode = comment.getEpisodeId() == null
                            ? null
                            : episodes.get(comment.getEpisodeId());
                    return new MyCommentView(
                            comment.getId(),
                            comment.getDramaId(),
                            drama == null ? null : drama.title(),
                            comment.getEpisodeId(),
                            episode == null ? null : episode.title(),
                            comment.getContent(),
                            comment.getLikeCount(),
                            comment.getReplyCount(),
                            comment.getStatus(),
                            comment.getCreateTime());
                })
                .toList();

        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 分页查询我的收藏。
     */
    public PageResult<FavoriteItem> listFavorites(Long userId, PageQuery page) {
        // 1. 统计并查询当前页
        long total = favoriteMapper.countByUser(userId);
        List<UserFavorite> favorites = total == 0
                ? List.of()
                : favoriteMapper.selectByUser(userId, page.offset(), page.pageSize());

        // 2. 批量补齐短剧信息
        Map<Long, DramaBriefDTO> dramas = loadDramas(favorites.stream()
                .map(UserFavorite::getDramaId).filter(Objects::nonNull).collect(Collectors.toSet()));

        // 3. 映射为列表项
        List<FavoriteItem> records = favorites.stream()
                .map(favorite -> {
                    DramaBriefDTO drama = dramas.get(favorite.getDramaId());
                    return new FavoriteItem(
                            favorite.getDramaId(),
                            drama == null ? null : drama.title(),
                            drama == null ? null : drama.subtitle(),
                            drama == null ? null : drama.coverUrl(),
                            drama == null ? null : drama.episodeCount(),
                            favorite.getCreateTime());
                })
                .toList();

        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 分页查询观看历史。
     */
    public PageResult<HistoryItem> listHistory(Long userId, PageQuery page) {
        // 1. 统计并查询当前页
        long total = historyMapper.countByUser(userId);
        List<WatchHistory> histories = total == 0
                ? List.of()
                : historyMapper.selectByUser(userId, page.offset(), page.pageSize());

        // 2. 批量补齐短剧和剧集信息
        List<HistoryItem> records = toHistoryItems(histories);

        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 首页继续观看：每部短剧取最近一条观看记录。
     */
    public List<HistoryItem> listContinueWatching(Long userId, Integer limit) {
        // 1. 限制条数，默认 3 条
        int size = limit == null ? 3 : Math.max(1, Math.min(limit, 10));
        // 2. 查询每部短剧最近一条记录并补齐标题/封面
        return toHistoryItems(historyMapper.selectLatestPerDrama(userId, size));
    }

    /**
     * 观看历史映射为列表项，批量补齐短剧和剧集信息。
     */
    private List<HistoryItem> toHistoryItems(List<WatchHistory> histories) {
        // 1. 收集短剧和剧集 ID，批量查询
        Map<Long, DramaBriefDTO> dramas = loadDramas(histories.stream()
                .map(WatchHistory::getDramaId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, EpisodeBriefDTO> episodes = loadEpisodes(histories.stream()
                .map(WatchHistory::getEpisodeId).filter(Objects::nonNull).collect(Collectors.toSet()));

        // 2. 映射为列表项，补齐失败时字段为空
        return histories.stream()
                .map(history -> {
                    DramaBriefDTO drama = dramas.get(history.getDramaId());
                    EpisodeBriefDTO episode = episodes.get(history.getEpisodeId());
                    return new HistoryItem(
                            history.getDramaId(),
                            drama == null ? null : drama.title(),
                            drama == null ? null : drama.coverUrl(),
                            history.getEpisodeId(),
                            episode == null ? null : episode.episodeNo(),
                            episode == null ? null : episode.title(),
                            episode == null ? null : episode.coverUrl(),
                            history.getPositionMs(),
                            history.getDurationMs(),
                            Integer.valueOf(1).equals(history.getFinished()),
                            history.getLastWatchTime());
                })
                .toList();
    }

    /**
     * 分页查询我点赞的短剧。
     */
    public PageResult<LikedDramaItem> listLikedDramas(Long userId, PageQuery page) {
        // 1. 统计并查询当前页
        long total = likeMapper.countLikedDramas(userId);
        List<UserLike> likes = total == 0
                ? List.of()
                : likeMapper.selectLikedDramas(userId, page.offset(), page.pageSize());

        // 2. 批量补齐短剧信息
        Map<Long, DramaBriefDTO> dramas = loadDramas(likes.stream()
                .map(UserLike::getTargetId).filter(Objects::nonNull).collect(Collectors.toSet()));

        // 3. 映射为列表项
        List<LikedDramaItem> records = likes.stream()
                .map(like -> {
                    DramaBriefDTO drama = dramas.get(like.getTargetId());
                    return new LikedDramaItem(
                            like.getTargetId(),
                            drama == null ? null : drama.title(),
                            drama == null ? null : drama.subtitle(),
                            drama == null ? null : drama.coverUrl(),
                            drama == null ? null : drama.episodeCount(),
                            like.getCreateTime());
                })
                .toList();

        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 查询个人中心互动计数。
     */
    public InteractionStats getStats(Long userId) {
        // 1. 单条 SQL 聚合四项计数，避免 4 次往返
        InteractionStatsRow row = statsMapper.selectStats(userId);
        if (row == null) {
            return new InteractionStats(0L, 0L, 0L, 0L);
        }
        return new InteractionStats(
                row.getLikedDramaCount() == null ? 0L : row.getLikedDramaCount(),
                row.getFavoriteCount() == null ? 0L : row.getFavoriteCount(),
                row.getCommentCount() == null ? 0L : row.getCommentCount(),
                row.getHistoryCount() == null ? 0L : row.getHistoryCount());
    }

    /**
     * 查询当前用户对短剧的点赞和收藏状态。
     */
    public DramaState getDramaState(Long userId, Long dramaId) {
        // 1. 点赞和收藏各查一次唯一键
        boolean liked = likeMapper.countByUserAndTarget(userId, TARGET_DRAMA, dramaId) > 0;
        boolean favorited = favoriteMapper.countByUserAndDrama(userId, dramaId) > 0;
        return new DramaState(dramaId, liked, favorited);
    }

    /**
     * 评论实体转列表项，补齐作者、回复目标和点赞状态。
     */
    private List<CommentView> toCommentViews(Long userId, List<Comment> comments) {
        // 1. 空列表直接返回
        if (comments.isEmpty()) {
            return List.of();
        }

        // 2. 一次批量补齐作者和回复目标用户
        Set<Long> userIds = new HashSet<>();
        for (Comment comment : comments) {
            if (comment.getUserId() != null) {
                userIds.add(comment.getUserId());
            }
            if (comment.getReplyToUserId() != null) {
                userIds.add(comment.getReplyToUserId());
            }
        }
        Map<Long, UserInfoDTO> users = loadUsers(userIds);

        // 3. 一次查出当前用户点赞过的评论
        Set<Long> commentIds = comments.stream()
                .map(Comment::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> likedIds = loadLikedCommentIds(userId, commentIds);

        // 4. 映射为列表项
        return comments.stream()
                .map(comment -> {
                    UserInfoDTO author = users.get(comment.getUserId());
                    UserInfoDTO replyTo = comment.getReplyToUserId() == null
                            ? null
                            : users.get(comment.getReplyToUserId());
                    return new CommentView(
                            comment.getId(),
                            comment.getDramaId(),
                            comment.getEpisodeId(),
                            comment.getUserId(),
                            author == null ? null : author.nickname(),
                            author == null ? null : author.avatarUrl(),
                            comment.getRootId(),
                            comment.getParentId(),
                            comment.getReplyToUserId(),
                            replyTo == null ? null : replyTo.nickname(),
                            comment.getContent(),
                            comment.getLikeCount(),
                            comment.getReplyCount(),
                            likedIds.contains(comment.getId()),
                            comment.getCreateTime());
                })
                .toList();
    }

    /**
     * 批量查询用户信息，失败时降级为空映射。
     */
    private Map<Long, UserInfoDTO> loadUsers(Collection<Long> ids) {
        Map<Long, UserInfoDTO> users = new HashMap<>();
        // 1. 按分片批量调用用户服务
        for (List<Long> chunk : chunks(ids)) {
            try {
                Result<List<UserInfoDTO>> result = userClient.getByIds(new IdBatchRequest(chunk));
                if (result.isSuccess() && result.getData() != null) {
                    result.getData().forEach(user -> users.put(user.id(), user));
                } else {
                    log.warn("批量查询用户失败: code={}, message={}", result.getCode(), result.getMessage());
                }
            } catch (Exception ex) {
                // 2. 单个分片失败只记录日志，不影响其余分片
                log.warn("批量查询用户异常: {}", ex.getMessage());
            }
        }
        return users;
    }

    /**
     * 批量查询短剧摘要，失败时降级为空映射。
     */
    private Map<Long, DramaBriefDTO> loadDramas(Collection<Long> ids) {
        Map<Long, DramaBriefDTO> dramas = new HashMap<>();
        // 1. 按分片批量调用短剧服务
        for (List<Long> chunk : chunks(ids)) {
            try {
                Result<List<DramaBriefDTO>> result = dramaClient.getDramas(new IdBatchRequest(chunk));
                if (result.isSuccess() && result.getData() != null) {
                    result.getData().forEach(drama -> dramas.put(drama.dramaId(), drama));
                } else {
                    log.warn("批量查询短剧失败: code={}, message={}", result.getCode(), result.getMessage());
                }
            } catch (Exception ex) {
                // 2. 单个分片失败只记录日志，不影响其余分片
                log.warn("批量查询短剧异常: {}", ex.getMessage());
            }
        }
        return dramas;
    }

    /**
     * 批量查询剧集摘要，失败时降级为空映射。
     */
    private Map<Long, EpisodeBriefDTO> loadEpisodes(Collection<Long> ids) {
        Map<Long, EpisodeBriefDTO> episodes = new HashMap<>();
        // 1. 按分片批量调用剧集接口
        for (List<Long> chunk : chunks(ids)) {
            try {
                Result<List<EpisodeBriefDTO>> result = dramaClient.getEpisodes(new IdBatchRequest(chunk));
                if (result.isSuccess() && result.getData() != null) {
                    result.getData().forEach(episode -> episodes.put(episode.episodeId(), episode));
                } else {
                    log.warn("批量查询剧集失败: code={}, message={}", result.getCode(), result.getMessage());
                }
            } catch (Exception ex) {
                // 2. 单个分片失败只记录日志，不影响其余分片
                log.warn("批量查询剧集异常: {}", ex.getMessage());
            }
        }
        return episodes;
    }

    /**
     * 查询当前用户点赞过的评论 ID。
     */
    private Set<Long> loadLikedCommentIds(Long userId, Set<Long> commentIds) {
        // 1. 空集合跳过查询
        if (commentIds.isEmpty()) {
            return Set.of();
        }

        // 2. 一次查出当前用户对这批评论的点赞记录
        List<UserLike> likes = likeMapper.selectList(new LambdaQueryWrapper<UserLike>()
                .eq(UserLike::getUserId, userId)
                .eq(UserLike::getTargetType, TARGET_COMMENT)
                .in(UserLike::getTargetId, commentIds));

        return likes.stream()
                .map(UserLike::getTargetId)
                .collect(Collectors.toSet());
    }

    /**
     * 去重并过滤非法 ID，再按 100 分片。
     */
    private List<List<Long>> chunks(Collection<Long> ids) {
        // 1. 去重并过滤 null/非正数
        Set<Long> unique = new LinkedHashSet<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && id > 0) {
                    unique.add(id);
                }
            }
        }
        if (unique.isEmpty()) {
            return List.of();
        }

        // 2. 按 100 分片，避免批量接口超过上限
        List<Long> all = new ArrayList<>(unique);
        List<List<Long>> result = new ArrayList<>();
        for (int start = 0; start < all.size(); start += BATCH_SIZE) {
            result.add(all.subList(start, Math.min(start + BATCH_SIZE, all.size())));
        }
        return result;
    }
}
