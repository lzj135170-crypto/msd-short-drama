package com.msd.drama.controller;

import com.msd.common.dto.DramaAiReviewResultRequest;
import com.msd.common.dto.DramaReviewCandidateDTO;
import com.msd.common.result.Result;
import com.msd.drama.service.DramaReviewService;
import com.msd.drama.service.DramaTagDictionaryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 短剧审核内部接口，只允许 content-service 直连。
 *
 * <p>放在 /internal-source/** 下，网关不路由；AI 结果只写 ai_review_*，
 * 不修改 review_status。</p>
 */
@RestController
@RequestMapping("/internal-source/reviews")
public class InternalDramaReviewController {

    // 短剧审核服务
    private final DramaReviewService reviewService;
    // 标签字典服务
    private final DramaTagDictionaryService tagDictionaryService;

    /**
     * 注入短剧审核服务和标签字典服务。
     */
    public InternalDramaReviewController(DramaReviewService reviewService,
                                         DramaTagDictionaryService tagDictionaryService) {
        this.reviewService = reviewService;
        this.tagDictionaryService = tagDictionaryService;
    }

    /**
     * 查询启用中的标签字典，供 content-service 生成标签建议。
     */
    @GetMapping("/tag-dictionary")
    public Result<List<String>> tagDictionary() {
        // 1. 只读返回，不修改标签关系表
        return Result.ok(tagDictionaryService.listVisibleNames());
    }

    /**
     * 查询待 AI 审核的短剧候选。
     */
    @GetMapping("/pending")
    public Result<List<DramaReviewCandidateDTO>> pending(
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "includeFailed", defaultValue = "false") boolean includeFailed) {
        // 1. 只返回待审且 AI 尚未通过的短剧；显式重试时包含失败项
        return Result.ok(reviewService.listAiReviewCandidates(limit, includeFailed));
    }

    /**
     * 查询待审短剧详情，供单条重审。
     */
    @GetMapping("/{dramaId}")
    public Result<DramaReviewCandidateDTO> detail(@PathVariable("dramaId") Long dramaId) {
        // 1. 非待审状态返回 data=null，调用方不得继续写回
        return Result.ok(reviewService.getPendingReview(dramaId));
    }

    /**
     * 写回 AI 审核结果；只在待审状态生效。
     */
    @PutMapping("/{dramaId}/ai-result")
    public Result<Boolean> writeAiResult(
            @PathVariable("dramaId") Long dramaId,
            @Valid @RequestBody DramaAiReviewResultRequest request) {
        // 1. 条件写回，人工审核已推进时返回 false
        return Result.ok(reviewService.writeAiResult(dramaId, request));
    }
}
