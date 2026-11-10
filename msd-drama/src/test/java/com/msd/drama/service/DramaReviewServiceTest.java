package com.msd.drama.service;

import com.msd.common.dto.DramaAiReviewResultRequest;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.event.DramaChangedAction;
import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 短剧审核状态机、AI 写回和降级测试。
 */
class DramaReviewServiceTest {

    private final DramaMapper dramaMapper = mock(DramaMapper.class);
    private final DramaReviewLogMapper reviewLogMapper = mock(DramaReviewLogMapper.class);
    private final CacheEvictor cacheEvictor = mock(CacheEvictor.class);
    private final DramaBloomFilterService bloomFilterService = mock(DramaBloomFilterService.class);
    private final DramaChangedPublisher changedPublisher = mock(DramaChangedPublisher.class);

    private final DramaReviewService service =
            new DramaReviewService(dramaMapper, reviewLogMapper, cacheEvictor,
                    bloomFilterService, changedPublisher);

    /**
     * 创作者可以提交自己的草稿。
     */
    @Test
    void creatorSubmitsOwnDraft() {
        when(dramaMapper.selectById(5003L))
                .thenReturn(drama(5003L, 1002L, 0, 0), drama(5003L, 1002L, 1, 1));
        when(dramaMapper.submitReview(5003L)).thenReturn(1);

        DramaReviewState state = service.submit(5003L, 1002L, "CREATOR");

        assertThat(state.reviewStatus()).isEqualTo(1);
        assertThat(state.aiReviewStatus()).isEqualTo(1);
        verify(dramaMapper).submitReview(5003L);

        ArgumentCaptor<DramaReviewLog> captor = ArgumentCaptor.forClass(DramaReviewLog.class);
        verify(reviewLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("SUBMIT");
        assertThat(captor.getValue().getFromStatus()).isZero();
        assertThat(captor.getValue().getToStatus()).isEqualTo(1);
    }

    /**
     * 创作者不能提交他人的短剧。
     */
    @Test
    void creatorCannotSubmitOthers() {
        when(dramaMapper.selectById(5003L)).thenReturn(drama(5003L, 1004L, 0, 0));

        assertThatThrownBy(() -> service.submit(5003L, 1002L, "CREATOR"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40300));
        verify(dramaMapper, never()).submitReview(any());
    }

    /**
     * 管理员通过后失效缓存并加入布隆过滤器。
     */
    @Test
    void approveEvictsCacheAndAddsBloom() {
        when(dramaMapper.selectById(5003L)).thenReturn(drama(5003L, 1002L, 2, 2));
        when(dramaMapper.approveReview(5003L, 1001L, "通过")).thenReturn(1);

        DramaReviewState state = service.review(5003L, 1001L, "ADMIN",
                new DramaReviewDecisionRequest("APPROVE", "通过"));

        assertThat(state.reviewStatus()).isEqualTo(2);
        verify(cacheEvictor).evictDetailAfterCommit(5003L);
        verify(cacheEvictor).evictHomeDebounced();
        verify(bloomFilterService).add(5003L);
        verify(changedPublisher).publishAfterCommit(5003L, DramaChangedAction.UPSERT);
    }

    /**
     * 驳回必须填写审核意见。
     */
    @Test
    void rejectRequiresRemark() {
        assertThatThrownBy(() -> service.review(5003L, 1001L, "ADMIN",
                new DramaReviewDecisionRequest("REJECT", " ")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40000));
        verify(dramaMapper, never()).rejectReview(any(), any(), any());
    }

    /**
     * 管理员驳回后返回驳回状态。
     */
    @Test
    void rejectReturnsRejectedState() {
        when(dramaMapper.selectById(5003L)).thenReturn(drama(5003L, 1002L, 3, 2));
        when(dramaMapper.rejectReview(5003L, 1001L, "节奏偏慢")).thenReturn(1);

        DramaReviewState state = service.review(5003L, 1001L, "ADMIN",
                new DramaReviewDecisionRequest("REJECT", "节奏偏慢"));

        assertThat(state.reviewStatus()).isEqualTo(3);
        verify(cacheEvictor).evictDetailAfterCommit(5003L);
        verify(cacheEvictor).evictHomeDebounced();
        verify(bloomFilterService, never()).add(any());
    }

    /**
     * 管理员下架后返回下架状态并失效缓存。
     */
    @Test
    void offlineReturnsOfflineState() {
        when(dramaMapper.selectById(5003L)).thenReturn(drama(5003L, 1002L, 4, 2));
        when(dramaMapper.offlineReview(5003L, 1001L, "临时下架")).thenReturn(1);

        DramaReviewState state = service.offline(5003L, 1001L, "ADMIN", "临时下架");

        assertThat(state.reviewStatus()).isEqualTo(4);
        verify(cacheEvictor).evictDetailAfterCommit(5003L);
        verify(cacheEvictor).evictHomeDebounced();
        verify(changedPublisher).publishAfterCommit(5003L, DramaChangedAction.DELETE);
    }

    /**
     * 状态已被其他请求改变时返回冲突。
     */
    @Test
    void conflictWhenStateChanged() {
        when(dramaMapper.approveReview(5003L, 1001L, "")).thenReturn(0);

        assertThatThrownBy(() -> service.review(5003L, 1001L, "ADMIN",
                new DramaReviewDecisionRequest("APPROVE", "")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40900));
    }

    /**
     * AI 状态只能是 2-4。
     */
    @Test
    void writeAiResultValidatesStatus() {
        assertThatThrownBy(() -> service.writeAiResult(5003L,
                new DramaAiReviewResultRequest(1, "{}")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40000));
    }

    /**
     * AI 结果条件写回成功返回 true。
     */
    @Test
    void writesAiResult() {
        when(dramaMapper.writeAiReviewResult(5003L, 2, "{}")).thenReturn(1);

        boolean updated = service.writeAiResult(5003L,
                new DramaAiReviewResultRequest(2, "{}"));

        assertThat(updated).isTrue();
    }

    /**
     * 重试 AI 失败项时，查询条件需要包含 ai_review_status=4。
     */
    @Test
    void includesFailedAiCandidatesWhenRequested() {
        DramaReviewCandidateRow row = new DramaReviewCandidateRow();
        row.setDramaId(5003L);
        row.setTitle("待重试短剧");
        when(dramaMapper.selectAiReviewCandidates(20, true)).thenReturn(List.of(row));

        List<DramaReviewCandidateDTO> candidates = service.listAiReviewCandidates(20, true);

        assertThat(candidates).singleElement()
                .satisfies(item -> assertThat(item.dramaId()).isEqualTo(5003L));
        verify(dramaMapper).selectAiReviewCandidates(20, true);
    }

    /**
     * 审核队列映射分页和 DTO 字段。
     */
    @Test
    void listsReviewPage() {
        DramaReviewCandidateRow row = new DramaReviewCandidateRow();
        row.setDramaId(5003L);
        row.setTitle("雾城谜案");
        row.setReviewStatus(1);
        row.setAiReviewStatus(1);
        row.setSubmitTime(LocalDateTime.of(2026, 10, 25, 12, 0));
        when(dramaMapper.countReviews(null, null)).thenReturn(1L);
        when(dramaMapper.selectReviewPage(null, null, 0, 20)).thenReturn(List.of(row));

        PageResult<DramaReviewCandidateDTO> page = service.listReviews(null, null, 1, 20);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).singleElement()
                .satisfies(item -> {
                    assertThat(item.dramaId()).isEqualTo(5003L);
                    assertThat(item.title()).isEqualTo("雾城谜案");
                    assertThat(item.submitTime()).isEqualTo("2026-10-25T12:00");
                });
    }

    /**
     * 构造短剧实体。
     */
    private Drama drama(Long id, Long authorId, Integer reviewStatus, Integer aiReviewStatus) {
        Drama drama = new Drama();
        drama.setId(id);
        drama.setAuthorId(authorId);
        drama.setReviewStatus(reviewStatus);
        drama.setAiReviewStatus(aiReviewStatus);
        drama.setDeleted(0);
        return drama;
    }
}
