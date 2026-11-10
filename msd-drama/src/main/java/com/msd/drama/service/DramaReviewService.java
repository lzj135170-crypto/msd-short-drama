package com.msd.drama.service;

import com.msd.common.auth.RoleGuard;
import com.msd.common.dto.DramaAiReviewResultRequest;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.dto.PageQuery;
import com.msd.common.event.DramaChangedAction;
import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
import com.msd.common.result.ResultCode;
import com.msd.drama.cache.CacheEvictor;
import com.msd.drama.cache.bloom.DramaBloomFilterService;
import com.msd.drama.dto.DramaReviewCandidateRow;
import com.msd.drama.dto.DramaReviewDecisionRequest;
import com.msd.drama.dto.DramaReviewState;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaReviewLog;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.DramaReviewLogMapper;
import com.msd.drama.mq.DramaChangedPublisher;
import com.msd.drama.mq.TranscriptDramaNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 短剧审核状态机与 AI 审核结果写回。
 *
 * <p>AI 只写 ai_review_*，人工审核是唯一的状态迁移入口；所有迁移使用条件 UPDATE，
 * 影响 0 行按冲突处理，避免并发覆盖。</p>
 */
@Service
public class DramaReviewService {

    /** 单次 AI 审核候选上限。 */
    private static final int MAX_AI_CANDIDATES = 20;
    /** AI 审核结果 JSON 最大长度。 */
    private static final int MAX_AI_RESULT_CHARS = 8000;

    // 短剧 Mapper
    private final DramaMapper dramaMapper;
    // 审核日志 Mapper
    private final DramaReviewLogMapper reviewLogMapper;
    // 多级缓存失效器
    private final CacheEvictor cacheEvictor;
    // 布隆过滤器
    private final DramaBloomFilterService bloomFilterService;
    // RAG 增量索引事件发布器，测试可传 null
    private final DramaChangedPublisher changedPublisher;
    // 审核通过后字幕索引补发器，测试可传 null
    private final TranscriptDramaNotifier transcriptNotifier;

    /**
     * 注入 Mapper、审核日志、缓存失效器、布隆过滤器、索引事件发布器和字幕补发器。
     */
    @Autowired
    public DramaReviewService(DramaMapper dramaMapper,
                              DramaReviewLogMapper reviewLogMapper,
                              CacheEvictor cacheEvictor,
                              DramaBloomFilterService bloomFilterService,
                              DramaChangedPublisher changedPublisher,
                              TranscriptDramaNotifier transcriptNotifier) {
        this.dramaMapper = dramaMapper;
        this.reviewLogMapper = reviewLogMapper;
        this.cacheEvictor = cacheEvictor;
        this.bloomFilterService = bloomFilterService;
        this.changedPublisher = changedPublisher;
        this.transcriptNotifier = transcriptNotifier;
    }

    /**
     * 兼容旧测试的构造器，不发布索引事件。
     */
    public DramaReviewService(DramaMapper dramaMapper,
                              DramaReviewLogMapper reviewLogMapper,
                              CacheEvictor cacheEvictor,
                              DramaBloomFilterService bloomFilterService) {
        this(dramaMapper, reviewLogMapper, cacheEvictor, bloomFilterService, null, null);
    }

    /**
     * 兼容旧测试的构造器，不补发字幕事件。
     */
    public DramaReviewService(DramaMapper dramaMapper,
                              DramaReviewLogMapper reviewLogMapper,
                              CacheEvictor cacheEvictor,
                              DramaBloomFilterService bloomFilterService,
                              DramaChangedPublisher changedPublisher) {
        this(dramaMapper, reviewLogMapper, cacheEvictor, bloomFilterService,
                changedPublisher, null);
    }

    /**
     * 查询待 AI 审核的短剧，默认不包含失败项。
     */
    public List<DramaReviewCandidateDTO> listAiReviewCandidates(Integer limit) {
        return listAiReviewCandidates(limit, false);
    }

    /**
     * 查询待 AI 审核的短剧，供 content-service 批量审核。
     *
     * @param includeFailed true 时同时返回 AI 失败项，用于人工触发重试
     */
    public List<DramaReviewCandidateDTO> listAiReviewCandidates(Integer limit,
                                                               boolean includeFailed) {
        // 1. 收敛批量大小，默认 20
        int size = limit == null ? MAX_AI_CANDIDATES
                : Math.max(1, Math.min(limit, MAX_AI_CANDIDATES));
        return dramaMapper.selectAiReviewCandidates(size, includeFailed).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * 查询待审短剧详情；不存在或已不在待审时返回 null。
     */
    public DramaReviewCandidateDTO getPendingReview(Long dramaId) {
        // 1. 只允许待审状态被 AI 重审
        if (dramaId == null || dramaId <= 0) {
            return null;
        }
        DramaReviewCandidateRow row = dramaMapper.selectReviewById(dramaId);
        if (row == null || !Integer.valueOf(1).equals(row.getReviewStatus())) {
            return null;
        }
        return toDto(row);
    }

    /**
     * 分页查询审核队列，供管理端使用。
     */
    public PageResult<DramaReviewCandidateDTO> listReviews(Integer reviewStatus,
                                                           Integer aiReviewStatus,
                                                           Integer pageNo,
                                                           Integer pageSize) {
        // 1. 归一化分页参数
        PageQuery page = PageQuery.of(pageNo, pageSize);
        long total = dramaMapper.countReviews(reviewStatus, aiReviewStatus);
        if (total <= 0) {
            return PageResult.empty(page.pageNo(), page.pageSize());
        }
        // 2. 查询当前页并转换为 DTO
        List<DramaReviewCandidateDTO> records = dramaMapper.selectReviewPage(
                        reviewStatus, aiReviewStatus, (int) page.offset(), page.pageSize())
                .stream()
                .map(this::toDto)
                .toList();
        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 写回 AI 审核结果；只在待审状态生效。
     *
     * @return true 表示写入成功，false 表示状态已变化
     */
    @Transactional
    public boolean writeAiResult(Long dramaId, DramaAiReviewResultRequest request) {
        // 1. 校验短剧 ID、AI 状态和 JSON 长度
        if (dramaId == null || dramaId <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "短剧 ID 不合法");
        }
        Integer status = request.aiReviewStatus();
        if (status == null || status < 2 || status > 4) {
            throw new BizException(ResultCode.PARAM_ERROR, "AI 审核状态只能为 2-4");
        }
        String json = request.aiReviewResult() == null ? "" : request.aiReviewResult().trim();
        if (json.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "AI 审核结果不能为空");
        }
        if (json.length() > MAX_AI_RESULT_CHARS) {
            throw new BizException(ResultCode.PARAM_ERROR, "AI 审核结果不能超过 8000 字");
        }
        // 2. 读取旧 AI 状态用于审计日志
        DramaReviewCandidateRow before = dramaMapper.selectReviewById(dramaId);

        // 3. 条件更新，人工审核已推进时不覆盖
        boolean updated = dramaMapper.writeAiReviewResult(dramaId, status, json) == 1;
        if (updated) {
            insertLog(dramaId, "AI_REVIEW",
                    before == null ? null : before.getAiReviewStatus(), status,
                    0L, "AI", "AI 审核结果写入");
        }
        return updated;
    }

    /**
     * 提交审核：草稿或驳回进入待审。
     */
    @Transactional
    public DramaReviewState submit(Long dramaId, Long userId, String role) {
        // 1. 校验角色和短剧归属
        RoleGuard.requireCreatorOrAdmin(role);
        Drama drama = requireDrama(dramaId);
        if (RoleGuard.isCreator(role) && !Objects.equals(drama.getAuthorId(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "只能提交自己的短剧");
        }
        // 2. 只有草稿或驳回可以提交
        if (!Integer.valueOf(0).equals(drama.getReviewStatus())
                && !Integer.valueOf(3).equals(drama.getReviewStatus())) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不能提交审核");
        }
        // 3. 条件更新，失败说明状态已被其他请求改变
        if (dramaMapper.submitReview(dramaId) != 1) {
            throw new BizException(ResultCode.CONFLICT, "提交审核失败，请刷新后重试");
        }
        insertLog(dramaId, "SUBMIT", drama.getReviewStatus(), 1, userId, "HUMAN", "提交审核");
        return loadState(dramaId);
    }

    /**
     * 人工审核：通过或驳回。
     */
    @Transactional
    public DramaReviewState review(Long dramaId, Long reviewerId, String role,
                                   DramaReviewDecisionRequest request) {
        // 1. 只允许管理员审核
        RoleGuard.requireAdmin(role);
        String decision = request.decision() == null
                ? "" : request.decision().trim().toUpperCase(Locale.ROOT);
        String remark = trimToEmpty(request.remark());

        // 2. 驳回必须填写原因，避免用户不知道如何修改
        if ("REJECT".equals(decision) && remark.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "驳回时必须填写审核意见");
        }

        // 3. 条件更新人工审核状态
        int updated = switch (decision) {
            case "APPROVE" -> dramaMapper.approveReview(dramaId, reviewerId, remark);
            case "REJECT" -> dramaMapper.rejectReview(dramaId, reviewerId, remark);
            default -> throw new BizException(ResultCode.PARAM_ERROR, "审核决策只能为 APPROVE 或 REJECT");
        };
        if (updated != 1) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不能审核，请刷新后重试");
        }

        // 4. 记录人工审核日志
        insertLog(dramaId, "APPROVE".equals(decision) ? "APPROVE" : "REJECT",
                1, "APPROVE".equals(decision) ? 2 : 3,
                reviewerId, "HUMAN", remark);

        // 5. 审核结果影响公开可见性，失效详情和首页缓存
        cacheEvictor.evictDetailAfterCommit(dramaId);
        cacheEvictor.evictHomeDebounced();
        if ("APPROVE".equals(decision)) {
            bloomFilterService.add(dramaId);
            // 6. 上架或重新上架后，提交后增量更新 RAG 索引
            publishChanged(dramaId, DramaChangedAction.UPSERT);
            // 7. 草稿期导入的字幕在审核通过后补建 transcript 索引
            if (transcriptNotifier != null) {
                transcriptNotifier.publishForDrama(dramaId);
            }
        }
        return loadState(dramaId);
    }

    /**
     * 下架已上架短剧。
     */
    @Transactional
    public DramaReviewState offline(Long dramaId, Long reviewerId, String role, String remark) {
        // 1. 只允许管理员下架
        RoleGuard.requireAdmin(role);
        // 2. 条件更新，只有已上架状态可以下架
        if (dramaMapper.offlineReview(dramaId, reviewerId, trimToEmpty(remark)) != 1) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不能下架，请刷新后重试");
        }
        insertLog(dramaId, "OFFLINE", 2, 4, reviewerId, "HUMAN", trimToEmpty(remark));
        // 3. 下架后公开详情和首页不再可见
        cacheEvictor.evictDetailAfterCommit(dramaId);
        cacheEvictor.evictHomeDebounced();
        // 4. 提交后从 RAG 索引移除，避免下架内容继续被检索
        publishChanged(dramaId, DramaChangedAction.DELETE);
        return loadState(dramaId);
    }

    /**
     * 查询某部短剧的审核日志，只允许管理员。
     */
    public List<DramaReviewLog> listLogs(Long dramaId, String role) {
        RoleGuard.requireAdmin(role);
        if (dramaId == null || dramaId <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "短剧 ID 不合法");
        }
        return reviewLogMapper.selectByDrama(dramaId, 50);
    }

    /**
     * 发布索引变更事件；发布器为空或发送失败都不影响审核结果。
     */
    private void publishChanged(Long dramaId, DramaChangedAction action) {
        if (changedPublisher != null) {
            changedPublisher.publishAfterCommit(dramaId, action);
        }
    }

    /**
     * 写入审核操作日志；日志失败随事务一起回滚。
     */
    private void insertLog(Long dramaId, String action, Integer fromStatus, Integer toStatus,
                           Long operatorId, String source, String remark) {
        DramaReviewLog log = new DramaReviewLog();
        log.setDramaId(dramaId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperatorId(operatorId);
        log.setSource(source);
        log.setRemark(remark == null ? "" : remark);
        reviewLogMapper.insert(log);
    }

    /**
     * 查询短剧，不存在或已删除时返回 404。
     */
    private Drama requireDrama(Long dramaId) {
        if (dramaId == null || dramaId <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "短剧 ID 不合法");
        }
        Drama drama = dramaMapper.selectById(dramaId);
        if (drama == null || !Integer.valueOf(0).equals(drama.getDeleted())) {
            throw new BizException(ResultCode.NOT_FOUND, "短剧不存在");
        }
        return drama;
    }

    /**
     * 读取最新审核状态，供操作响应返回。
     */
    private DramaReviewState loadState(Long dramaId) {
        Drama drama = dramaMapper.selectById(dramaId);
        if (drama == null) {
            throw new BizException(ResultCode.NOT_FOUND, "短剧不存在");
        }
        return new DramaReviewState(dramaId, drama.getReviewStatus(),
                drama.getAiReviewStatus(), drama.getPublishTime(), drama.getReviewTime());
    }

    /**
     * 查询行转换为跨服务 DTO。
     */
    private DramaReviewCandidateDTO toDto(DramaReviewCandidateRow row) {
        return new DramaReviewCandidateDTO(
                row.getDramaId(),
                row.getTitle(),
                row.getSubtitle(),
                row.getDescription(),
                row.getCoverUrl(),
                row.getActors(),
                row.getAuthorId(),
                row.getCategoryId(),
                row.getEpisodeCount(),
                row.getReviewStatus(),
                row.getAiReviewStatus(),
                row.getAiReviewResult(),
                timeText(row.getSubmitTime()),
                timeText(row.getAiReviewTime()),
                timeText(row.getReviewTime()),
                timeText(row.getPublishTime()));
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
    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
