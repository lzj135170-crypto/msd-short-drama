package com.msd.order.service;

import com.msd.common.exception.BizException;
import com.msd.common.result.Result;
import com.msd.common.result.ResultCode;
import com.msd.order.client.DramaSourceClient;
import com.msd.order.dto.EpisodeSourceDTO;
import com.msd.order.dto.PlaybackInfo;
import com.msd.order.mapper.EpisodeUnlockMapper;
import org.springframework.stereotype.Service;

/**
 * 播放授权：读取剧集源，结合解锁记录决定是否返回视频地址。
 */
@Service
public class PlaybackService {

    // drama 播放源 Feign 客户端
    private final DramaSourceClient dramaSourceClient;
    // 剧集解锁 Mapper
    private final EpisodeUnlockMapper episodeUnlockMapper;

    /**
     * 注入 drama 播放源客户端和解锁 Mapper。
     */
    public PlaybackService(DramaSourceClient dramaSourceClient,
                           EpisodeUnlockMapper episodeUnlockMapper) {
        this.dramaSourceClient = dramaSourceClient;
        this.episodeUnlockMapper = episodeUnlockMapper;
    }

    /**
     * 查询播放信息；付费未解锁时 videoUrl 为 null。
     */
    public PlaybackInfo getPlayback(Long userId, Long episodeId) {
        // 1. 调 drama 查询可播放的剧集源
        Result<EpisodeSourceDTO> sourceResult = dramaSourceClient.getSource(episodeId);
        if (!sourceResult.isSuccess()) {
            throw new BizException(sourceResult.getCode(), sourceResult.getMessage());
        }
        EpisodeSourceDTO source = sourceResult.getData();

        // 2. drama 已过滤剧集在线和短剧上架，null 统一按不可播放处理
        if (source == null) {
            throw new BizException(ResultCode.NOT_FOUND, "剧集不存在或已下架");
        }

        // 3. 免费集直接可播；付费集查当前用户的解锁记录
        long pricePoints = source.pricePoints() == null ? 0L : source.pricePoints();
        boolean free = pricePoints == 0L;
        boolean unlocked = free
                || episodeUnlockMapper.selectByUserAndEpisode(userId, episodeId) != null;

        // 4. 未解锁时不下发视频地址
        String videoUrl = unlocked ? source.videoUrl() : null;

        return new PlaybackInfo(
                source.dramaId(),
                source.dramaTitle(),
                source.episodeId(),
                source.episodeNo(),
                source.episodeTitle(),
                source.coverUrl(),
                source.durationSeconds(),
                pricePoints,
                free,
                unlocked,
                videoUrl
        );
    }
}
