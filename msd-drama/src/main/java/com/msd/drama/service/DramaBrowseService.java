package com.msd.drama.service;

import com.msd.common.dto.DramaBriefDTO;
import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.drama.cache.BloomGuard;
import com.msd.drama.dto.DramaDetail;
import com.msd.drama.dto.HomeFeed;
import com.msd.drama.mapper.DramaMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 首页推荐和短剧详情查询。
 *
 * <p>读路径委托给 {@link DramaBrowseCacheService}，让多级缓存切面通过代理生效。</p>
 */
@Service
public class DramaBrowseService {

    // 短剧 Mapper，用于批量查询摘要
    private final DramaMapper dramaMapper;
    // 带多级缓存的首页与详情加载器
    private final DramaBrowseCacheService browseCacheService;
    // 布隆过滤器提供者，未启用时为空
    private final ObjectProvider<BloomGuard> bloomGuardProvider;

    /**
     * 注入短剧 Mapper、缓存加载器和布隆过滤器提供者。
     */
    public DramaBrowseService(DramaMapper dramaMapper,
                              DramaBrowseCacheService browseCacheService,
                              ObjectProvider<BloomGuard> bloomGuardProvider) {
        this.dramaMapper = dramaMapper;
        this.browseCacheService = browseCacheService;
        this.bloomGuardProvider = bloomGuardProvider;
    }

    /**
     * 查询首页聚合数据。
     */
    public HomeFeed getHome() {
        return browseCacheService.loadHome();
    }

    /**
     * 查询已上架短剧详情。
     */
    public DramaDetail getDetail(Long dramaId) {
        // 1. 优先读多级缓存，未命中时回源数据库
        DramaDetail detail = browseCacheService.loadDetail(dramaId);
        // 2. 短剧不存在或未上架，转 404
        if (detail == null) {
            throw new BizException(ResultCode.NOT_FOUND, "短剧不存在或未上架");
        }
        return detail;
    }

    /**
     * 按 ID 批量查询已上架短剧摘要。
     */
    public List<DramaBriefDTO> getBriefs(List<Long> ids) {
        // 1. 空集合直接返回
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // 2. 过滤 null，布隆可用时先排除确定不存在的 ID
        List<Long> candidates = ids.stream().filter(Objects::nonNull).toList();
        BloomGuard guard = bloomGuardProvider.getIfAvailable();
        if (guard != null) {
            candidates = candidates.stream().filter(guard::mightContain).toList();
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 3. 只返回已上架短剧
        return dramaMapper.selectPublicByIds(candidates).stream()
                .map(drama -> new DramaBriefDTO(
                        drama.getId(),
                        drama.getTitle(),
                        drama.getSubtitle(),
                        drama.getCoverUrl(),
                        drama.getEpisodeCount(),
                        drama.getCategoryId()))
                .toList();
    }
}
