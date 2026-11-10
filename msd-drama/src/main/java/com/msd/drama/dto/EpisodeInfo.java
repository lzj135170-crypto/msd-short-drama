package com.msd.drama.dto;

/**
 * 剧集解锁所需的公开信息。
 *
 * @param episodeId  剧集 ID
 * @param dramaId    短剧 ID
 * @param episodeNo  第几集
 * @param pricePoints 解锁所需积分，0 表示免费
 * @param status     状态：0 下线，1 上线
 * @param unlockCount 解锁次数
 * @param dramaPublished 所属短剧是否已上架
 */
public record EpisodeInfo(
        Long episodeId,
        Long dramaId,
        Integer episodeNo,
        Long pricePoints,
        Integer status,
        Long unlockCount,
        boolean dramaPublished
) {
}
