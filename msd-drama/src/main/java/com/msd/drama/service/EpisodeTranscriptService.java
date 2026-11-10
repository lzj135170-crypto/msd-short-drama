package com.msd.drama.service;

import com.msd.common.auth.RoleGuard;
import com.msd.common.dto.EpisodeTranscriptDTO;
import com.msd.common.dto.PageQuery;
import com.msd.common.dto.TranscriptSegmentDTO;
import com.msd.common.event.DramaChangedAction;
import com.msd.common.exception.BizException;
import com.msd.common.result.PageResult;
import com.msd.common.result.ResultCode;
import com.msd.drama.dto.AsrTranscriptRequest;
import com.msd.drama.dto.SubtitleImportRequest;
import com.msd.drama.dto.SubtitleImportResult;
import com.msd.drama.dto.TranscriptEpisodeRow;
import com.msd.drama.entity.Drama;
import com.msd.drama.entity.DramaEpisode;
import com.msd.drama.entity.EpisodeTranscriptSegment;
import com.msd.drama.mapper.DramaMapper;
import com.msd.drama.mapper.EpisodeMapper;
import com.msd.drama.mapper.EpisodeSubtitleMapper;
import com.msd.drama.mapper.EpisodeTranscriptSegmentMapper;
import com.msd.drama.mq.TranscriptChangedPublisher;
import com.msd.drama.subtitle.SubtitleCue;
import com.msd.drama.subtitle.SubtitleParser;
import com.msd.drama.subtitle.TranscriptSafetyFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 分集字幕导入、ASR 回传和导出。
 *
 * <p>字幕只服务剧情检索，不改变视频或审核状态；写入前做确定性安全过滤，
 * 入库后通过 TRANSCRIPT_CHANGED 事件驱动 content 重建 transcript 索引。</p>
 */
@Service
public class EpisodeTranscriptService {

    /** 单集最多保留的片段数。 */
    private static final int MAX_SEGMENTS = 2_000;

    // 短剧、剧集和字幕 Mapper
    private final DramaMapper dramaMapper;
    private final EpisodeMapper episodeMapper;
    private final EpisodeSubtitleMapper subtitleMapper;
    private final EpisodeTranscriptSegmentMapper segmentMapper;
    // SRT/VTT 解析和确定性安全过滤
    private final SubtitleParser parser;
    private final TranscriptSafetyFilter safetyFilter;
    // 字幕索引事件发布器
    private final TranscriptChangedPublisher publisher;

    /**
     * 注入字幕链路依赖。
     */
    public EpisodeTranscriptService(DramaMapper dramaMapper,
                                    EpisodeMapper episodeMapper,
                                    EpisodeSubtitleMapper subtitleMapper,
                                    EpisodeTranscriptSegmentMapper segmentMapper,
                                    SubtitleParser parser,
                                    TranscriptSafetyFilter safetyFilter,
                                    TranscriptChangedPublisher publisher) {
        this.dramaMapper = dramaMapper;
        this.episodeMapper = episodeMapper;
        this.subtitleMapper = subtitleMapper;
        this.segmentMapper = segmentMapper;
        this.parser = parser;
        this.safetyFilter = safetyFilter;
        this.publisher = publisher;
    }

    /**
     * 创作者导入 SRT/VTT 字幕。
     */
    @Transactional
    public SubtitleImportResult importSubtitle(Long dramaId, Long episodeId, Long userId,
                                               String role, SubtitleImportRequest request) {
        // 1. 创作者只能操作自己的短剧
        RoleGuard.requireCreatorOrAdmin(role);
        Drama drama = requireDrama(dramaId);
        if (RoleGuard.isCreator(role) && !Objects.equals(drama.getAuthorId(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "只能编辑自己的短剧");
        }
        DramaEpisode episode = requireEpisode(dramaId, episodeId);

        // 2. 解析字幕并做安全过滤
        String format = request.format().trim().toUpperCase();
        if (!"SRT".equals(format) && !"VTT".equals(format)) {
            throw new BizException(ResultCode.PARAM_ERROR, "字幕格式只支持 SRT 或 VTT");
        }
        List<SubtitleCue> cues = parser.parse(format, request.content());
        if (cues.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "没有解析到有效的字幕片段");
        }
        return replaceSegments(episode, request.language(), format, "UPLOAD",
                "inline:" + sha256(request.content()), cues);
    }

    /**
     * ASR worker 回传带时间戳的片段。
     */
    @Transactional
    public SubtitleImportResult importAsr(Long episodeId, AsrTranscriptRequest request) {
        // 1. 校验剧集存在
        DramaEpisode episode = requireEpisodeById(episodeId);
        // 2. ASR 片段转统一时间轴
        List<SubtitleCue> cues = new ArrayList<>();
        for (AsrTranscriptRequest.Segment segment : request.segments()) {
            if (segment == null || segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            int start = segment.startMs() == null ? 0 : Math.max(segment.startMs(), 0);
            int end = segment.endMs() == null ? start : Math.max(segment.endMs(), start);
            if (end <= start) {
                continue;
            }
            cues.add(new SubtitleCue(start, end, segment.text()));
        }
        if (cues.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "没有可用的 ASR 字幕片段");
        }
        return replaceSegments(episode, request.language(), "ASR", "ASR",
                "asr:" + episodeId, cues);
    }

    /**
     * 分页查询有字幕的在线剧集 ID，供 content 全量重建字幕索引。
     */
    public PageResult<Long> listTranscriptEpisodeIds(Integer pageNo, Integer pageSize) {
        PageQuery page = PageQuery.of(pageNo, pageSize);
        long total = episodeMapper.countTranscriptEpisodes();
        if (total <= 0) {
            return PageResult.empty(page.pageNo(), page.pageSize());
        }
        List<Long> records = episodeMapper.selectTranscriptEpisodeIds(
                (int) page.offset(), page.pageSize());
        return PageResult.of(page.pageNo(), page.pageSize(), total, records);
    }

    /**
     * 导出单集可见字幕，供 content 建立索引；不可见或无字幕返回 null。
     */
    public EpisodeTranscriptDTO export(Long episodeId) {
        // 1. 只导出在线剧集且已上架短剧
        if (episodeId == null || episodeId <= 0) {
            return null;
        }
        TranscriptEpisodeRow row = episodeMapper.selectTranscriptEpisode(episodeId);
        if (row == null) {
            return null;
        }
        // 2. 读取可见片段；没有片段时返回空列表，content 会删除旧索引
        List<TranscriptSegmentDTO> segments = segmentMapper
                .selectVisibleByEpisodeId(episodeId).stream()
                .map(segment -> new TranscriptSegmentDTO(
                        segment.getId(), segment.getStartMs(), segment.getEndMs(),
                        segment.getText()))
                .toList();
        return new EpisodeTranscriptDTO(
                row.getDramaId(), row.getDramaTitle(), row.getEpisodeId(),
                row.getEpisodeNo(), row.getEpisodeTitle(), row.getPricePoints(), segments);
    }

    /**
     * 替换某集某语言的全部片段，并发布索引事件。
     */
    private SubtitleImportResult replaceSegments(DramaEpisode episode, String language,
                                                 String format, String source,
                                                 String objectKey, List<SubtitleCue> cues) {
        // 1. 过滤高风险内容和重复时间轴
        Set<String> seen = new LinkedHashSet<>();
        List<SubtitleCue> valid = new ArrayList<>();
        int filtered = 0;
        for (SubtitleCue cue : cues) {
            String text = safetyFilter.sanitize(cue.text());
            if (text == null) {
                filtered += 1;
                continue;
            }
            String key = cue.startMs() + "|" + cue.endMs() + "|" + text;
            if (!seen.add(key)) {
                filtered += 1;
                continue;
            }
            valid.add(new SubtitleCue(cue.startMs(), cue.endMs(), text));
            if (valid.size() >= MAX_SEGMENTS) {
                break;
            }
        }
        if (valid.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "字幕没有可入库的有效内容");
        }

        // 2. 覆盖字幕元数据并替换片段
        subtitleMapper.upsert(episode.getId(), language, format, objectKey, source, valid.size());
        Long subtitleId = subtitleMapper.selectIdByEpisodeAndLanguage(episode.getId(), language);
        if (subtitleId == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "字幕写入失败");
        }
        segmentMapper.deleteBySubtitleId(subtitleId);
        List<EpisodeTranscriptSegment> segments = new ArrayList<>(valid.size());
        for (SubtitleCue cue : valid) {
            EpisodeTranscriptSegment segment = new EpisodeTranscriptSegment();
            segment.setSubtitleId(subtitleId);
            segment.setEpisodeId(episode.getId());
            segment.setDramaId(episode.getDramaId());
            segment.setEpisodeNo(episode.getEpisodeNo());
            segment.setStartMs(cue.startMs());
            segment.setEndMs(cue.endMs());
            segment.setText(cue.text());
            segment.setStatus(1);
            segments.add(segment);
        }
        segmentMapper.insertBatch(segments);

        // 3. 事务提交后重建该集 transcript 索引
        publisher.publishAfterCommit(episode.getId(), DramaChangedAction.UPSERT);
        return new SubtitleImportResult(subtitleId, valid.size(), filtered);
    }

    /**
     * 读取短剧，不存在或已删除时返回 404。
     */
    private Drama requireDrama(Long dramaId) {
        if (dramaId == null || dramaId <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "短剧 ID 不合法");
        }
        Drama drama = dramaMapper.selectById(dramaId);
        if (drama == null || !Integer.valueOf(0).equals(drama.getDeleted())) {
            throw new BizException(ResultCode.NOT_FOUND, "短剧不存在");
        }
        return drama;
    }

    /**
     * 校验剧集属于指定短剧。
     */
    private DramaEpisode requireEpisode(Long dramaId, Long episodeId) {
        DramaEpisode episode = requireEpisodeById(episodeId);
        if (!Objects.equals(episode.getDramaId(), dramaId)) {
            throw new BizException(ResultCode.NOT_FOUND, "剧集不属于该短剧");
        }
        return episode;
    }

    /**
     * 按 ID 读取剧集。
     */
    private DramaEpisode requireEpisodeById(Long episodeId) {
        if (episodeId == null || episodeId <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "剧集 ID 不合法");
        }
        DramaEpisode episode = episodeMapper.selectById(episodeId);
        if (episode == null) {
            throw new BizException(ResultCode.NOT_FOUND, "剧集不存在");
        }
        return episode;
    }

    /**
     * SHA-256 内容摘要，避免把整份字幕作为 objectKey。
     */
    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(content.hashCode());
        }
    }
}
