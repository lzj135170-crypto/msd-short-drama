package com.msd.drama.controller;

import com.msd.common.dto.DramaBriefDTO;
import com.msd.common.dto.IdBatchRequest;
import com.msd.common.result.Result;
import com.msd.drama.dto.DramaDetail;
import com.msd.drama.dto.HomeFeed;
import com.msd.drama.service.DramaBrowseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端首页和短剧详情接口。
 *
 * <p>经网关访问时需要登录：/api/drama/internal/home、/api/drama/internal/dramas/{id}。</p>
 */
@RestController
@RequestMapping("/internal")
public class DramaBrowseController {

    // 首页聚合与短剧详情查询服务
    private final DramaBrowseService dramaBrowseService;

    /**
     * 注入首页与详情查询服务。
     */
    public DramaBrowseController(DramaBrowseService dramaBrowseService) {
        this.dramaBrowseService = dramaBrowseService;
    }

    /**
     * 查询首页聚合数据。
     */
    @GetMapping("/home")
    public Result<HomeFeed> home() {
        // 1. 返回轮播、热播、最新和分类
        return Result.ok(dramaBrowseService.getHome());
    }

    /**
     * 查询短剧详情。
     */
    @GetMapping("/dramas/{dramaId}")
    public Result<DramaDetail> detail(@PathVariable("dramaId") Long dramaId) {
        // 1. 只返回已上架短剧和在线剧集
        return Result.ok(dramaBrowseService.getDetail(dramaId));
    }

    /**
     * 批量查询短剧摘要，供互动列表补齐标题和封面。
     */
    @PostMapping("/dramas/batch")
    public Result<List<DramaBriefDTO>> batch(@Valid @RequestBody IdBatchRequest request) {
        // 1. 只返回已上架短剧
        return Result.ok(dramaBrowseService.getBriefs(request.ids()));
    }
}
