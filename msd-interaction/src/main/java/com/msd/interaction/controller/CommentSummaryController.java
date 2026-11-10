package com.msd.interaction.controller;

import com.msd.common.result.Result;
import com.msd.interaction.dto.CommentSummaryView;
import com.msd.interaction.service.CommentSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论课代表接口，经网关访问。
 */
@RestController
@RequestMapping("/internal/ai/comments")
public class CommentSummaryController {

    // 评论摘要服务
    private final CommentSummaryService summaryService;

    /**
     * 注入评论摘要服务。
     */
    public CommentSummaryController(CommentSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /**
     * 查询某部短剧或剧集的评论课代表。
     */
    @GetMapping("/summary")
    public Result<CommentSummaryView> summary(
            @RequestParam("dramaId") Long dramaId,
            @RequestParam(value = "episodeId", required = false) Long episodeId) {
        // 1. 摘要失败返回降级空结果，不阻断评论列表
        return Result.ok(summaryService.getSummary(dramaId, episodeId));
    }
}
