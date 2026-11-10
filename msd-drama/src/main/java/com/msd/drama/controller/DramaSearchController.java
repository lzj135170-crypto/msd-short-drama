package com.msd.drama.controller;

import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.drama.dto.CategoryView;
import com.msd.drama.dto.DramaCard;
import com.msd.drama.service.DramaSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端分类与短剧搜索接口。
 *
 * <p>经网关访问：/api/drama/internal/categories、/api/drama/internal/search/dramas。</p>
 */
@RestController
@RequestMapping("/internal")
public class DramaSearchController {

    // 分类与搜索服务
    private final DramaSearchService dramaSearchService;

    /**
     * 注入分类与搜索服务。
     */
    public DramaSearchController(DramaSearchService dramaSearchService) {
        this.dramaSearchService = dramaSearchService;
    }

    /**
     * 查询启用中的分类，供搜索页筛选。
     */
    @GetMapping("/categories")
    public Result<List<CategoryView>> categories() {
        // 1. 只返回 status=1 的分类
        return Result.ok(dramaSearchService.listCategories());
    }

    /**
     * 搜索已上架短剧，支持关键词、分类、排序和分页。
     */
    @GetMapping("/search/dramas")
    public Result<PageResult<DramaCard>> search(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        // 1. 只返回已上架短剧，参数在服务层归一化
        return Result.ok(dramaSearchService.search(keyword, categoryId, sort, pageNo, pageSize));
    }
}
