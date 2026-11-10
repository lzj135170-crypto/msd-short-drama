package com.msd.order.service;

import com.msd.common.dto.DramaBriefDTO;
import com.msd.common.dto.EpisodeBriefDTO;
import com.msd.common.dto.IdBatchRequest;
import com.msd.common.dto.PageQuery;
import com.msd.common.result.PageResult;
import com.msd.common.result.Result;
import com.msd.order.client.DramaBriefClient;
import com.msd.order.client.DramaClient;
import com.msd.order.dto.DramaUnlocks;
import com.msd.order.dto.PointsBalance;
import com.msd.order.dto.UnlockState;
import com.msd.order.dto.UnlockedEpisodeItem;
import com.msd.order.entity.EpisodeUnlock;
import com.msd.order.entity.PointsAccount;
import com.msd.order.mapper.EpisodeUnlockMapper;
import com.msd.order.mapper.PointsAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单域只读查询，供测试台展示积分和解锁状态。
 */
@Service
public class OrderQueryService {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(OrderQueryService.class);

    // 积分账户 Mapper
    private final PointsAccountMapper pointsAccountMapper;
    // 剧集解锁 Mapper
    private final EpisodeUnlockMapper episodeUnlockMapper;
    // 剧集简要信息客户端
    private final DramaClient dramaClient;
    // 短剧简要信息客户端
    private final DramaBriefClient dramaBriefClient;

    /**
     * 注入积分账户、解锁 Mapper 和 drama 批量客户端。
     */
    public OrderQueryService(PointsAccountMapper pointsAccountMapper,
                             EpisodeUnlockMapper episodeUnlockMapper,
                             DramaClient dramaClient,
                             DramaBriefClient dramaBriefClient) {
        this.pointsAccountMapper = pointsAccountMapper;
        this.episodeUnlockMapper = episodeUnlockMapper;
        this.dramaClient = dramaClient;
        this.dramaBriefClient = dramaBriefClient;
    }

    /**
     * 查询用户积分余额，账户不存在时返回 exists=false。
     */
    public PointsBalance getPoints(Long userId) {
        // 1. 按用户主键查询积分账户
        PointsAccount account = pointsAccountMapper.selectById(userId);
        // 2. 不存在时返回空状态，避免把没有积分账户当成系统错误
        if (account == null) {
            return new PointsBalance(userId, 0L, false);
        }
        return new PointsBalance(userId, account.getBalance(), true);
    }

    /**
     * 查询用户对某集的解锁状态。
     */
    public UnlockState getUnlockState(Long userId, Long episodeId) {
        // 1. 查询解锁权益
        EpisodeUnlock unlock = episodeUnlockMapper.selectByUserAndEpisode(userId, episodeId);
        // 2. 未解锁时返回零值
        if (unlock == null) {
            return new UnlockState(episodeId, false, 0L, 0L);
        }
        // 3. 已解锁时返回记录 ID 和当时的积分快照
        return new UnlockState(episodeId, true, unlock.getId(), unlock.getPointsCost());
    }

    /**
     * 查询用户在某部短剧下已解锁的付费剧集。
     */
    public DramaUnlocks getDramaUnlocks(Long userId, Long dramaId) {
        // 1. 按解锁时间升序查询，免费集不在表中
        List<Long> episodeIds = episodeUnlockMapper.selectByUserAndDrama(userId, dramaId).stream()
                .map(EpisodeUnlock::getEpisodeId)
                .toList();
        return new DramaUnlocks(dramaId, episodeIds);
    }

    /**
     * 分页查询已解锁剧集，批量补齐短剧和剧集标题。
     */
    public PageResult<UnlockedEpisodeItem> getUnlockedEpisodes(Long userId,
                                                               Integer pageNo,
                                                               Integer pageSize) {
        // 1. 统计并查询当前页
        PageQuery page = PageQuery.of(pageNo, pageSize);
        long total = episodeUnlockMapper.countByUser(userId);
        if (total <= 0) {
            return PageResult.empty(page.pageNo(), page.pageSize());
        }
        List<EpisodeUnlock> unlocks = episodeUnlockMapper.selectPageByUser(
                userId, page.offset(), page.pageSize());

        // 2. 批量补齐剧集和短剧信息，失败时字段为空
        Map<Long, EpisodeBriefDTO> episodes = loadEpisodes(unlocks.stream()
                .map(EpisodeUnlock::getEpisodeId).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<Long, DramaBriefDTO> dramas = loadDramas(unlocks.stream()
                .map(EpisodeUnlock::getDramaId).filter(Objects::nonNull)
                .collect(Collectors.toSet()));

        // 3. 映射为列表项
        List<UnlockedEpisodeItem> records = unlocks.stream()
                .map(unlock -> {
                    EpisodeBriefDTO episode = episodes.get(unlock.getEpisodeId());
                    DramaBriefDTO drama = dramas.get(unlock.getDramaId());
                    return new UnlockedEpisodeItem(
                            unlock.getId(),
                            unlock.getDramaId(),
                            drama == null ? null : drama.title(),
                            drama == null ? null : drama.coverUrl(),
                            unlock.getEpisodeId(),
                            episode == null ? null : episode.episodeNo(),
                            episode == null ? null : episode.title(),
                            episode == null ? null : episode.coverUrl(),
                            unlock.getPointsCost(),
                            unlock.getCreateTime());
                })
                .toList();
        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 批量查询剧集信息，失败时返回空 Map。
     */
    private Map<Long, EpisodeBriefDTO> loadEpisodes(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Result<List<EpisodeBriefDTO>> response = dramaClient.batchEpisodes(
                    new IdBatchRequest(List.copyOf(ids)));
            if (response == null || !response.isSuccess() || response.getData() == null) {
                return Map.of();
            }
            return response.getData().stream()
                    .collect(Collectors.toMap(EpisodeBriefDTO::episodeId,
                            Function.identity(), (left, right) -> left));
        } catch (Exception e) {
            log.warn("unlocked episodes lookup failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 批量查询短剧信息，失败时返回空 Map。
     */
    private Map<Long, DramaBriefDTO> loadDramas(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Result<List<DramaBriefDTO>> response = dramaBriefClient.batch(
                    new IdBatchRequest(List.copyOf(ids)));
            if (response == null || !response.isSuccess() || response.getData() == null) {
                return Map.of();
            }
            return response.getData().stream()
                    .collect(Collectors.toMap(DramaBriefDTO::dramaId,
                            Function.identity(), (left, right) -> left));
        } catch (Exception e) {
            log.warn("unlocked dramas lookup failed: {}", e.getMessage());
            return Map.of();
        }
    }
}
