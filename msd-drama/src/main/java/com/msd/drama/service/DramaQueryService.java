package com.msd.drama.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.drama.dto.DramaStats;
import com.msd.drama.dto.EpisodeView;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaEpisode;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.EpisodeMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 短剧与剧集只读查询，供测试台展示计数。
 */
@Service
public class DramaQueryService {

    // 短剧 Mapper
    private final DramaMapper dramaMapper;
    // 剧集 Mapper
    private final EpisodeMapper episodeMapper;

    /**
     * 注入短剧和剧集 Mapper。
     */
    public DramaQueryService(DramaMapper dramaMapper, EpisodeMapper episodeMapper) {
        this.dramaMapper = dramaMapper;
        this.episodeMapper = episodeMapper;
    }

    /**
     * 查询短剧的基础信息和冗余计数。
     */
    public DramaStats getStats(Long dramaId) {
        // 1. 查询短剧，不存在时抛业务异常
        Drama drama = requireDrama(dramaId);
        // 2. 转成统计 DTO
        return new DramaStats(
                drama.getId(),
                drama.getTitle(),
                drama.getEpisodeCount(),
                drama.getReviewStatus(),
                drama.getViewCount(),
                drama.getLikeCount(),
                drama.getFavoriteCount(),
                drama.getCommentCount()
        );
    }

    /**
     * 查询短剧下的剧集列表和计数。
     */
    public List<EpisodeView> listEpisodes(Long dramaId) {
        // 1. 先确认短剧存在，避免返回空列表掩盖错误 ID
        requireDrama(dramaId);
        // 2. 按集数升序查询并转换
        return episodeMapper.selectByDramaId(dramaId).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 查询短剧，不存在时抛出 404。
     */
    private Drama requireDrama(Long dramaId) {
        Drama drama = dramaMapper.selectById(dramaId);
        if (drama == null) {
            throw new BizException(ResultCode.NOT_FOUND, "短剧不存在");
        }
        return drama;
    }

    /**
     * 剧集实体转展示 DTO。
     */
    private EpisodeView toView(DramaEpisode episode) {
        return new EpisodeView(
                episode.getId(),
                episode.getDramaId(),
                episode.getEpisodeNo(),
                episode.getTitle(),
                episode.getPricePoints(),
                episode.getStatus(),
                episode.getPlayCount(),
                episode.getLikeCount(),
                episode.getCommentCount(),
                episode.getUnlockCount()
        );
    }
}
