package com.msd.drama.service;

import com.msd.common.dto.EpisodeBriefDTO;
import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.drama.cache.CacheEvictor;
import com.msd.drama.dto.EpisodeInfo;
import com.msd.drama.dto.EpisodeSource;
import com.msd.drama.dto.EpisodeSourceRow;
import com.msd.drama.dto.UnlockRefRequest;
import com.msd.drama.entity.DramaEpisode;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.EpisodeMapper;
import com.msd.drama.mapper.EpisodeUnlockRefMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 剧集查询与解锁引用服务。
 */
@Service
public class EpisodeService {

    // 剧集 Mapper
    private final EpisodeMapper episodeMapper;
    // 解锁引用 Mapper
    private final EpisodeUnlockRefMapper unlockRefMapper;
    // 短剧 Mapper
    private final DramaMapper dramaMapper;
    // 缓存失效器
    private final CacheEvictor cacheEvictor;

    /**
     * 注入剧集、解锁引用、短剧 Mapper 和缓存失效器。
     */
    public EpisodeService(EpisodeMapper episodeMapper,
                          EpisodeUnlockRefMapper unlockRefMapper,
                          DramaMapper dramaMapper,
                          CacheEvictor cacheEvictor) {
        this.episodeMapper = episodeMapper;
        this.unlockRefMapper = unlockRefMapper;
        this.dramaMapper = dramaMapper;
        this.cacheEvictor = cacheEvictor;
    }

    /**
     * 查询剧集解锁所需信息。
     */
    public EpisodeInfo getEpisode(Long episodeId) {
        // 1. 按主键查询剧集
        DramaEpisode episode = episodeMapper.selectById(episodeId);
        // 2. 不存在时抛业务异常
        if (episode == null) {
            throw new BizException(ResultCode.NOT_FOUND, "剧集不存在");
        }
        // 3. 同时返回父短剧是否仍然上架，供解锁流程校验
        boolean dramaPublished = dramaMapper.selectPublicById(episode.getDramaId()) != null;
        return new EpisodeInfo(
                episode.getId(),
                episode.getDramaId(),
                episode.getEpisodeNo(),
                episode.getPricePoints(),
                episode.getStatus(),
                episode.getUnlockCount(),
                dramaPublished
        );
    }

    /**
     * 查询可播放的剧集源；不可见时返回 null。
     */
    public EpisodeSource getSource(Long episodeId) {
        // 1. 查询时已经过滤剧集在线和短剧上架
        EpisodeSourceRow row = episodeMapper.selectSourceById(episodeId);
        if (row == null) {
            return null;
        }
        // 2. 转成内部播放源 DTO
        return new EpisodeSource(
                row.getEpisodeId(),
                row.getDramaId(),
                row.getDramaTitle(),
                row.getEpisodeNo(),
                row.getEpisodeTitle(),
                row.getVideoUrl(),
                row.getCoverUrl(),
                row.getDurationSeconds(),
                row.getPricePoints()
        );
    }

    /**
     * 按 ID 批量查询剧集摘要，供观看历史和我的评论补齐标题。
     */
    public List<EpisodeBriefDTO> getBriefs(List<Long> ids) {
        // 1. 空集合直接返回
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // 2. 返回存在的剧集，包含下线剧集和状态
        return episodeMapper.selectByIds(ids).stream()
                .map(episode -> new EpisodeBriefDTO(
                        episode.getId(),
                        episode.getDramaId(),
                        episode.getEpisodeNo(),
                        episode.getTitle(),
                        episode.getCoverUrl(),
                        episode.getDurationSeconds(),
                        episode.getStatus()))
                .toList();
    }

    /**
     * 幂等写入 drama 侧解锁引用，并增加剧集解锁计数。
     *
     * @return true 表示首次写入并增加计数，false 表示重复请求
     */
    @Transactional
    public boolean addUnlockRef(Long episodeId, UnlockRefRequest request) {
        // 1. 确认剧集存在，避免写入孤儿引用
        DramaEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BizException(ResultCode.NOT_FOUND, "剧集不存在");
        }
        // 2. 按 unlock_id 幂等插入引用
        int inserted = unlockRefMapper.insertIgnore(
                request.unlockId(), episodeId, request.userId());
        // 3. 已存在说明重复调用，不再增加计数
        if (inserted == 0) {
            return false;
        }
        // 4. 首次写入才增加解锁计数，更新失败让本地事务回滚
        if (episodeMapper.incrementUnlockCount(episodeId) != 1) {
            throw new BizException(ResultCode.NOT_FOUND, "剧集不存在");
        }
        // 5. 事务提交后失效详情缓存，500ms 后再删一次
        cacheEvictor.evictDetailAfterCommit(episode.getDramaId());
        // 6. 返回首次写入标记
        return true;
    }
}
