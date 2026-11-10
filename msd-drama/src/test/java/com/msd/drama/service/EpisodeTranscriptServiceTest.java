package com.msd.drama.service;

import com.msd.common.dto.EpisodeTranscriptDTO;
import com.msd.common.event.DramaChangedAction;
import com.msd.common.exception.BizException;
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
import com.msd.drama.subtitle.SubtitleParser;
import com.msd.drama.subtitle.TranscriptSafetyFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字幕导入、ASR 回传、导出和权限测试。
 */
class EpisodeTranscriptServiceTest {

    private final DramaMapper dramaMapper = mock(DramaMapper.class);
    private final EpisodeMapper episodeMapper = mock(EpisodeMapper.class);
    private final EpisodeSubtitleMapper subtitleMapper = mock(EpisodeSubtitleMapper.class);
    private final EpisodeTranscriptSegmentMapper segmentMapper =
            mock(EpisodeTranscriptSegmentMapper.class);
    private final TranscriptChangedPublisher publisher = mock(TranscriptChangedPublisher.class);

    private final EpisodeTranscriptService service = new EpisodeTranscriptService(
            dramaMapper, episodeMapper, subtitleMapper, segmentMapper,
            new SubtitleParser(), new TranscriptSafetyFilter(), publisher);

    /**
     * 创作者导入 SRT 后写入片段并发布 UPSERT。
     */
    @Test
    void importsSubtitleAndPublishesUpsert() {
        when(dramaMapper.selectById(5005L)).thenReturn(drama());
        when(episodeMapper.selectById(6005L)).thenReturn(episode());
        when(subtitleMapper.selectIdByEpisodeAndLanguage(6005L, "zh")).thenReturn(9001L);

        SubtitleImportResult result = service.importSubtitle(5005L, 6005L, 1002L, "CREATOR",
                new SubtitleImportRequest("zh", "SRT", srt()));

        assertThat(result.subtitleId()).isEqualTo(9001L);
        assertThat(result.segmentCount()).isEqualTo(2);
        verify(subtitleMapper).upsert(eq(6005L), eq("zh"), eq("SRT"), anyString(),
                eq("UPLOAD"), eq(2));
        verify(segmentMapper).deleteBySubtitleId(9001L);
        ArgumentCaptor<List<EpisodeTranscriptSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(segmentMapper).insertBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getDramaId()).isEqualTo(5005L);
        assertThat(captor.getValue().get(0).getEpisodeNo()).isEqualTo(1);
        verify(publisher).publishAfterCommit(6005L, DramaChangedAction.UPSERT);
    }

    /**
     * 创作者不能导入他人短剧字幕。
     */
    @Test
    void rejectsOtherCreator() {
        when(dramaMapper.selectById(5005L)).thenReturn(drama());

        assertThatThrownBy(() -> service.importSubtitle(5005L, 6005L, 9999L, "CREATOR",
                new SubtitleImportRequest("zh", "SRT", srt())))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40300));
        verify(segmentMapper, never()).insertBatch(any());
    }

    /**
     * 不支持的字幕格式返回参数错误。
     */
    @Test
    void rejectsUnsupportedFormat() {
        when(dramaMapper.selectById(5005L)).thenReturn(drama());
        when(episodeMapper.selectById(6005L)).thenReturn(episode());

        assertThatThrownBy(() -> service.importSubtitle(5005L, 6005L, 1002L, "CREATOR",
                new SubtitleImportRequest("zh", "TXT", srt())))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(40000));
    }

    /**
     * ASR 回传片段写入 ASR 来源。
     */
    @Test
    void importsAsrSegments() {
        when(episodeMapper.selectById(6005L)).thenReturn(episode());
        when(subtitleMapper.selectIdByEpisodeAndLanguage(6005L, "zh")).thenReturn(9002L);

        SubtitleImportResult result = service.importAsr(6005L, new AsrTranscriptRequest("zh",
                List.of(new AsrTranscriptRequest.Segment(1000, 3000, "第一句"))));

        assertThat(result.segmentCount()).isEqualTo(1);
        verify(subtitleMapper).upsert(6005L, "zh", "ASR", "asr:6005", "ASR", 1);
        verify(publisher).publishAfterCommit(6005L, DramaChangedAction.UPSERT);
    }

    /**
     * 导出只返回可见剧集的片段。
     */
    @Test
    void exportsVisibleTranscript() {
        TranscriptEpisodeRow row = new TranscriptEpisodeRow();
        row.setEpisodeId(6005L);
        row.setDramaId(5005L);
        row.setDramaTitle("长安第一女仵作");
        row.setEpisodeNo(1);
        row.setEpisodeTitle("第1集 雨夜尸案");
        row.setPricePoints(0);
        when(episodeMapper.selectTranscriptEpisode(6005L)).thenReturn(row);
        EpisodeTranscriptSegment segment = new EpisodeTranscriptSegment();
        segment.setId(1L);
        segment.setStartMs(1000);
        segment.setEndMs(3000);
        segment.setText("第一句");
        when(segmentMapper.selectVisibleByEpisodeId(6005L)).thenReturn(List.of(segment));

        EpisodeTranscriptDTO transcript = service.export(6005L);

        assertThat(transcript).isNotNull();
        assertThat(transcript.segments()).singleElement()
                .satisfies(item -> {
                    assertThat(item.startMs()).isEqualTo(1000);
                    assertThat(item.text()).isEqualTo("第一句");
                });
    }

    /**
     * 不可见剧集导出 null。
     */
    @Test
    void exportsNullWhenInvisible() {
        when(episodeMapper.selectTranscriptEpisode(6005L)).thenReturn(null);

        assertThat(service.export(6005L)).isNull();
    }

    /**
     * 构造短剧。
     */
    private Drama drama() {
        Drama drama = new Drama();
        drama.setId(5005L);
        drama.setAuthorId(1002L);
        drama.setDeleted(0);
        return drama;
    }

    /**
     * 构造剧集。
     */
    private DramaEpisode episode() {
        DramaEpisode episode = new DramaEpisode();
        episode.setId(6005L);
        episode.setDramaId(5005L);
        episode.setEpisodeNo(1);
        return episode;
    }

    /**
     * 两段 SRT。
     */
    private String srt() {
        return """
                1
                00:00:01,000 --> 00:00:03,000
                第一句

                2
                00:00:04,000 --> 00:00:06,000
                第二句
                """;
    }
}
