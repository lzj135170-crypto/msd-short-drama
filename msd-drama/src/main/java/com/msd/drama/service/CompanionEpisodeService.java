package com.msd.drama.service;

import com.msd.common.constant.EpisodeSummaryStatus;
import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.dto.SummaryClaimDTO;
import com.msd.common.dto.SummaryWritebackRequest;
import com.msd.common.exception.BizException;
import com.msd.common.result.ResultCode;
import com.msd.drama.dto.CompanionEpisodeRow;
import com.msd.drama.mapper.EpisodeMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 伴看剧集元信息与摘要状态服务。
 *
 * <p>摘要生成在 content-service 完成；本服务只负责可见性校验、认领和回写。</p>
 */
@Service
public class CompanionEpisodeService {

    // 简介截断长度，避免把整段简介塞进摘要生成提示
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    // 摘要最大长度，与 t_drama_episode.summary 列宽一致
    private static final int MAX_SUMMARY_LENGTH = 1000;

    // 剧集 Mapper
    private final EpisodeMapper episodeMapper;

    /**
     * 注入剧集 Mapper。
     */
    public CompanionEpisodeService(EpisodeMapper episodeMapper) {
        this.episodeMapper = episodeMapper;
    }

    /**
     * 查询 AI 伴看所需的剧集元信息；不可见时返回 null。
     */
    public CompanionEpisodeDTO getCompanionEpisode(Long episodeId) {
        // 1. 按可见性条件查询剧集和短剧元信息
        CompanionEpisodeRow row = episodeMapper.selectCompanionEpisodeById(episodeId);
        if (row == null) {
            return null;
        }
        // 2. 转换时归一空摘要并截断简介
        return new CompanionEpisodeDTO(
                row.getEpisodeId(),
                row.getDramaId(),
                row.getDramaTitle(),
                row.getEpisodeNo(),
                row.getEpisodeTitle(),
                nullToEmpty(row.getSummary()),
                row.getSummaryStatus(),
                truncate(row.getDramaDescription(), MAX_DESCRIPTION_LENGTH),
                row.getPricePoints() == null ? 0 : row.getPricePoints()
        );
    }

    /**
     * 查询可批量生成摘要的在线剧集 ID。
     *
     * <p>limit 限制在 1-50，避免运维接口一次拉取过多剧集。</p>
     */
    public List<Long> listSummaryCandidates(Long dramaId, Integer limit) {
        // 1. 参数非法时返回空列表，调用方不需要区分
        if (dramaId == null || dramaId <= 0) {
            return List.of();
        }
        // 2. 收敛批量大小，默认 20
        int size = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        return episodeMapper.selectSummaryCandidates(dramaId, size);
    }

    /**
     * 认领摘要生成权；剧集不可见时返回 null。
     *
     * <p>已有摘要直接返回；认领失败说明其他实例正在生成或已写入，重新读取最新状态。</p>
     */
    public SummaryClaimDTO claimSummary(Long episodeId) {
        // 1. 先确认剧集可见并读取当前摘要
        CompanionEpisodeRow row = episodeMapper.selectCompanionEpisodeById(episodeId);
        if (row == null) {
            return null;
        }
        // 2. 已有摘要时无需认领，直接返回给调用方
        if (hasText(row.getSummary())) {
            return new SummaryClaimDTO(false, row.getSummary(), row.getSummaryStatus());
        }
        // 3. 条件更新认领，只有空摘要且状态可重试时才成功
        if (episodeMapper.claimSummary(episodeId) == 1) {
            return new SummaryClaimDTO(true, "", EpisodeSummaryStatus.GENERATING);
        }
        // 4. 认领失败时重新读取，调用方据此决定等待还是重试
        CompanionEpisodeRow latest = episodeMapper.selectCompanionEpisodeById(episodeId);
        if (latest == null) {
            return null;
        }
        return new SummaryClaimDTO(false, nullToEmpty(latest.getSummary()), latest.getSummaryStatus());
    }

    /**
     * 回写生成的摘要；已有摘要时不会覆盖。
     *
     * @return true 表示写入成功，false 表示剧集不存在或已有摘要
     */
    public boolean writebackSummary(Long episodeId, SummaryWritebackRequest request) {
        // 1. 归一摘要并校验长度，防止绕过接口校验写入超长内容
        String summary = request.summary() == null ? "" : request.summary().trim();
        if (summary.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "摘要不能为空");
        }
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            throw new BizException(ResultCode.PARAM_ERROR, "摘要不能超过 1000 字");
        }
        // 2. 条件写入，不覆盖并发写入或种子摘要
        return episodeMapper.writebackSummary(episodeId, summary) == 1;
    }

    /**
     * 释放摘要认领并标记失败，允许后续重试。
     *
     * @return true 表示释放成功
     */
    public boolean releaseSummaryClaim(Long episodeId) {
        // 1. 只释放仍处于生成中的记录，避免覆盖已写入的摘要
        return episodeMapper.releaseSummaryClaim(episodeId) == 1;
    }

    /**
     * 判断字符串是否包含非空白字符。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 把 null 归一为空字符串。
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 按字符数截断文本，null 归一为空字符串。
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
