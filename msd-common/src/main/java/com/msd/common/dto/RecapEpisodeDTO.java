package com.msd.common.dto;

/**
 * 前情提要使用的分集摘要数据。
 *
 * @param episodeId     剧集 ID
 * @param episodeNo     集数序号
 * @param title         剧集标题
 * @param summary       分集摘要，缺失时为空字符串
 * @param pricePoints   解锁所需积分，0 表示免费
 * @param summaryStatus 摘要状态：0 缺失，1 生成中，2 可用，3 失败
 */
public record RecapEpisodeDTO(
        Long episodeId,
        Integer episodeNo,
        String title,
        String summary,
        Integer pricePoints,
        Integer summaryStatus
) {

    /**
     * 统一空值和默认值，避免调用方判空。
     */
    public RecapEpisodeDTO {
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        pricePoints = pricePoints == null ? 0 : pricePoints;
        summaryStatus = summaryStatus == null ? 0 : summaryStatus;
    }
}
