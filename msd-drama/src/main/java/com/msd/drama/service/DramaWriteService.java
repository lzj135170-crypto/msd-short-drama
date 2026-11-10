package com.msd.drama.service;

import com.msd.common.auth.RoleGuard;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.dto.PageQuery;
import com.msd.common.event.DramaChangedAction;
import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
import com.msd.common.result.ResultCode;
import com.msd.drama.cache.CacheEvictor;
import com.msd.drama.dto.CreatorDramaDetail;
import com.msd.drama.dto.CreatorEpisodeView;
import com.msd.drama.dto.DramaReviewState;
import com.msd.drama.dto.DramaWriteRequest;
import com.msd.drama.dto.EpisodeWriteItem;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaEpisode;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.EpisodeMapper;
import com.msd.drama.mq.TranscriptChangedPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 创作者短剧草稿与剧集编辑。
 *
 * <p>只有草稿或驳回状态可以编辑；上架内容需先下架。编辑只写业务表，
 * 审核和发布仍由 {@link DramaReviewService} 完成。</p>
 */
@Service
public class DramaWriteService {

    // 短剧 Mapper
    private final DramaMapper dramaMapper;
    // 剧集 Mapper
    private final EpisodeMapper episodeMapper;
    // 审核服务，负责提交审核
    private final DramaReviewService reviewService;
    // 缓存失效器
    private final CacheEvictor cacheEvictor;
    // 分集字幕索引事件发布器，测试可传 null
    private final TranscriptChangedPublisher transcriptPublisher;

    /**
     * 注入 Mapper、审核服务、缓存失效器和字幕事件发布器。
     */
    @Autowired
    public DramaWriteService(DramaMapper dramaMapper,
                             EpisodeMapper episodeMapper,
                             DramaReviewService reviewService,
                             CacheEvictor cacheEvictor,
                             TranscriptChangedPublisher transcriptPublisher) {
        this.dramaMapper = dramaMapper;
        this.episodeMapper = episodeMapper;
        this.reviewService = reviewService;
        this.cacheEvictor = cacheEvictor;
        this.transcriptPublisher = transcriptPublisher;
    }

    /**
     * 兼容旧测试的构造器，不发布字幕事件。
     */
    public DramaWriteService(DramaMapper dramaMapper,
                             EpisodeMapper episodeMapper,
                             DramaReviewService reviewService,
                             CacheEvictor cacheEvictor) {
        this(dramaMapper, episodeMapper, reviewService, cacheEvictor, null);
    }

    /**
     * 分页查询当前创作者的短剧。
     */
    public PageResult<DramaReviewCandidateDTO> listMine(Long userId,
                                                        String role,
                                                        Integer pageNo,
                                                        Integer pageSize) {
        // 1. 创作者和管理员可以进入工作台
        RoleGuard.requireCreatorOrAdmin(role);
        PageQuery page = PageQuery.of(pageNo, pageSize);
        long total = dramaMapper.countByAuthor(userId);
        if (total <= 0) {
            return PageResult.empty(page.pageNo(), page.pageSize());
        }
        // 2. 查询并转换为审核视图，前端直接复用状态字段
        List<DramaReviewCandidateDTO> records = dramaMapper.selectByAuthor(
                        userId, (int) page.offset(), page.pageSize())
                .stream()
                .map(this::toDto)
                .toList();
        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 查询自己的短剧详情和剧集，管理员可查看任意短剧。
     */
    public CreatorDramaDetail getMine(Long dramaId, Long userId, String role) {
        // 1. 校验角色、归属和删除状态
        RoleGuard.requireCreatorOrAdmin(role);
        Drama drama = requireAccessible(dramaId, userId, role);
        // 2. 编辑页需要包含下线剧集
        List<CreatorEpisodeView> episodes = episodeMapper.selectByDramaId(dramaId).stream()
                .map(this::toEpisodeView)
                .toList();
        return new CreatorDramaDetail(toDto(drama), episodes);
    }

    /**
     * 创建草稿短剧和剧集。
     */
    @Transactional
    public CreatorDramaDetail create(Long userId, String role, DramaWriteRequest request) {
        // 1. 校验角色和剧集集数
        RoleGuard.requireCreatorOrAdmin(role);
        List<EpisodeWriteItem> episodes = validateEpisodes(request.episodes());

        // 2. 写入短剧草稿
        Drama drama = new Drama();
        drama.setTitle(request.title().trim());
        drama.setSubtitle(nullToEmpty(request.subtitle()));
        drama.setCoverUrl(nullToEmpty(request.coverUrl()));
        drama.setDescription(nullToEmpty(request.description()));
        drama.setCategoryId(request.categoryId());
        drama.setAuthorId(userId);
        drama.setActors(nullToEmpty(request.actors()));
        drama.setEpisodeCount(episodes.size());
        drama.setReviewStatus(0);
        drama.setAiReviewStatus(0);
        drama.setDeleted(0);
        dramaMapper.insert(drama);

        // 3. 写入剧集，视频地址由 Demo 固定为空
        upsertEpisodes(drama.getId(), episodes);
        return getMine(drama.getId(), userId, role);
    }

    /**
     * 编辑草稿或驳回的短剧；已上架内容必须先下架。
     */
    @Transactional
    public CreatorDramaDetail update(Long dramaId,
                                     Long userId,
                                     String role,
                                     DramaWriteRequest request) {
        // 1. 校验角色、归属和可编辑状态
        RoleGuard.requireCreatorOrAdmin(role);
        Drama drama = requireAccessible(dramaId, userId, role);
        if (!Integer.valueOf(0).equals(drama.getReviewStatus())
                && !Integer.valueOf(3).equals(drama.getReviewStatus())) {
            throw new BizException(ResultCode.CONFLICT, "已上架内容请先下架再编辑");
        }
        List<EpisodeWriteItem> episodes = validateEpisodes(request.episodes());

        // 2. 更新短剧基础信息
        drama.setTitle(request.title().trim());
        drama.setSubtitle(nullToEmpty(request.subtitle()));
        drama.setCoverUrl(nullToEmpty(request.coverUrl()));
        drama.setDescription(nullToEmpty(request.description()));
        drama.setCategoryId(request.categoryId());
        drama.setActors(nullToEmpty(request.actors()));
        drama.setEpisodeCount(episodes.size());
        dramaMapper.updateById(drama);

        // 3. 新增/更新剧集，未提交的旧剧集下线
        List<DramaEpisode> before = episodeMapper.selectByDramaId(dramaId);
        upsertEpisodes(dramaId, episodes);
        episodeMapper.offlineMissingEpisodes(dramaId,
                episodes.stream().map(EpisodeWriteItem::episodeNo).toList());
        publishTranscriptDeletes(before, dramaId);

        // 4. 草稿不会出现在公开缓存，但可能在驳回前缓存过
        cacheEvictor.evictDetailAfterCommit(dramaId);
        cacheEvictor.evictHomeDebounced();
        return getMine(dramaId, userId, role);
    }

    /**
     * 删除草稿或驳回的短剧，使用软删除保留数据。
     */
    @Transactional
    public boolean delete(Long dramaId, Long userId, String role) {
        // 1. 校验角色、归属和可删除状态
        RoleGuard.requireCreatorOrAdmin(role);
        Drama drama = requireAccessible(dramaId, userId, role);
        if (!Integer.valueOf(0).equals(drama.getReviewStatus())
                && !Integer.valueOf(3).equals(drama.getReviewStatus())) {
            throw new BizException(ResultCode.CONFLICT, "只有草稿或驳回的短剧可以删除");
        }
        // 2. 条件软删除，失败说明状态已变化
        if (dramaMapper.softDeleteDraft(dramaId) != 1) {
            throw new BizException(ResultCode.CONFLICT, "删除失败，请刷新后重试");
        }
        // 3. 失效可能存在的公开缓存
        cacheEvictor.evictDetailAfterCommit(dramaId);
        cacheEvictor.evictHomeDebounced();
        return true;
    }

    /**
     * 提交审核，复用审核状态机。
     */
    public DramaReviewState submit(Long dramaId, Long userId, String role) {
        return reviewService.submit(dramaId, userId, role);
    }

    /**
     * 校验剧集集数唯一，并归一默认值。
     */
    private List<EpisodeWriteItem> validateEpisodes(List<EpisodeWriteItem> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "至少需要一集");
        }
        Set<Integer> seen = new HashSet<>();
        return episodes.stream()
                .map(item -> new EpisodeWriteItem(
                        item.episodeNo(),
                        nullToEmpty(item.title()),
                        item.durationSeconds() == null ? 0 : Math.max(item.durationSeconds(), 0),
                        item.pricePoints() == null ? 0L : Math.max(item.pricePoints(), 0L),
                        item.status() == null ? 1 : item.status()))
                .peek(item -> {
                    if (!seen.add(item.episodeNo())) {
                        throw new BizException(ResultCode.PARAM_ERROR, "集数不能重复");
                    }
                })
                .toList();
    }

    /**
     * 对本次被下线或删除的剧集发布字幕索引删除事件。
     */
    private void publishTranscriptDeletes(List<DramaEpisode> before, Long dramaId) {
        if (transcriptPublisher == null || before == null || before.isEmpty()) {
            return;
        }
        Set<Long> onlineAfter = new HashSet<>();
        for (DramaEpisode episode : episodeMapper.selectByDramaId(dramaId)) {
            if (Integer.valueOf(1).equals(episode.getStatus())) {
                onlineAfter.add(episode.getId());
            }
        }
        for (DramaEpisode episode : before) {
            if (Integer.valueOf(1).equals(episode.getStatus())
                    && !onlineAfter.contains(episode.getId())) {
                transcriptPublisher.publishAfterCommit(
                        episode.getId(), DramaChangedAction.DELETE);
            }
        }
    }

    /**
     * 写入或更新剧集。
     */
    private void upsertEpisodes(Long dramaId, List<EpisodeWriteItem> episodes) {
        for (EpisodeWriteItem episode : episodes) {
            episodeMapper.upsertEpisode(dramaId, episode.episodeNo(), episode.title(),
                    episode.durationSeconds(), episode.pricePoints(), episode.status());
        }
    }

    /**
     * 查询短剧并校验归属。
     */
    private Drama requireAccessible(Long dramaId, Long userId, String role) {
        if (dramaId == null || dramaId <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "短剧 ID 不合法");
        }
        Drama drama = dramaMapper.selectById(dramaId);
        if (drama == null || !Integer.valueOf(0).equals(drama.getDeleted())) {
            throw new BizException(ResultCode.NOT_FOUND, "短剧不存在");
        }
        if (RoleGuard.isCreator(role) && !Objects.equals(drama.getAuthorId(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "只能编辑自己的短剧");
        }
        return drama;
    }

    /**
     * 短剧实体转审核视图，复用状态字段。
     */
    private DramaReviewCandidateDTO toDto(Drama drama) {
        return new DramaReviewCandidateDTO(
                drama.getId(),
                drama.getTitle(),
                drama.getSubtitle(),
                drama.getDescription(),
                drama.getCoverUrl(),
                drama.getActors(),
                drama.getAuthorId(),
                drama.getCategoryId(),
                drama.getEpisodeCount(),
                drama.getReviewStatus(),
                drama.getAiReviewStatus(),
                drama.getAiReviewResult(),
                timeText(drama.getSubmitTime()),
                timeText(drama.getAiReviewTime()),
                timeText(drama.getReviewTime()),
                timeText(drama.getPublishTime()));
    }

    /**
     * 剧集实体转编辑视图。
     */
    private CreatorEpisodeView toEpisodeView(DramaEpisode episode) {
        return new CreatorEpisodeView(
                episode.getId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                episode.getCoverUrl(),
                episode.getDurationSeconds(),
                episode.getPricePoints(),
                episode.getStatus());
    }

    /**
     * 时间转字符串，null 保持 null。
     */
    private static String timeText(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    /**
     * null 转为空字符串。
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
