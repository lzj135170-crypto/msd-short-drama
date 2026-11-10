package com.msd.content.companion;

import com.msd.common.result.Result;
import com.msd.content.client.DramaCompanionClient;
import com.msd.content.dto.EpisodeSummaryView;
import com.msd.content.dto.SummaryWarmupResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 摘要批量预热的统计和降级测试。
 */
class EpisodeSummaryWarmupServiceTest {

    private final DramaCompanionClient dramaClient = mock(DramaCompanionClient.class);
    private final EpisodeSummaryService summaryService = mock(EpisodeSummaryService.class);

    private final EpisodeSummaryWarmupService service =
            new EpisodeSummaryWarmupService(dramaClient, summaryService);

    /**
     * 按来源统计生成、跳过和失败，并汇总单集降级标记。
     */
    @Test
    void countsGeneratedSkippedAndFailed() {
        when(dramaClient.summaryCandidates(5001L, 10))
                .thenReturn(Result.ok(List.of(6001L, 6002L, 6003L)));
        when(summaryService.getSummary(6001L))
                .thenReturn(view(6001L, EpisodeSummaryService.SOURCE_GENERATED, List.of()));
        when(summaryService.getSummary(6002L))
                .thenReturn(view(6002L, EpisodeSummaryService.SOURCE_SEED, List.of()));
        when(summaryService.getSummary(6003L))
                .thenReturn(view(6003L, EpisodeSummaryService.SOURCE_MISSING,
                        List.of(EpisodeSummaryService.FLAG_GENERATION)));

        SummaryWarmupResult result = service.warmup(5001L, 10);

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.generated()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.degradedFlags())
                .containsExactly(EpisodeSummaryService.FLAG_GENERATION);
    }

    /**
     * drama 不可用时返回整体降级，不抛异常。
     */
    @Test
    void degradesWhenDramaUnavailable() {
        when(dramaClient.summaryCandidates(5001L, 10)).thenReturn(null);

        SummaryWarmupResult result = service.warmup(5001L, 10);

        assertThat(result.scanned()).isZero();
        assertThat(result.degradedFlags()).containsExactly("drama");
    }

    /**
     * limit 收敛到 1-20，避免一次拉取过多剧集。
     */
    @Test
    void clampsLimit() {
        when(dramaClient.summaryCandidates(5001L, 20)).thenReturn(Result.ok(List.of()));

        SummaryWarmupResult result = service.warmup(5001L, 99);

        assertThat(result.scanned()).isZero();
        verify(dramaClient).summaryCandidates(5001L, 20);
    }

    /**
     * 单集生成抛出异常时继续处理后续剧集。
     */
    @Test
    void continuesAfterSingleEpisodeFailure() {
        when(dramaClient.summaryCandidates(5001L, 10))
                .thenReturn(Result.ok(List.of(6001L, 6002L)));
        when(summaryService.getSummary(6001L)).thenThrow(new IllegalStateException("boom"));
        when(summaryService.getSummary(6002L))
                .thenReturn(view(6002L, EpisodeSummaryService.SOURCE_GENERATED, List.of()));

        SummaryWarmupResult result = service.warmup(5001L, 10);

        assertThat(result.generated()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.degradedFlags())
                .containsExactly(EpisodeSummaryService.FLAG_GENERATION);
    }

    /**
     * 构造摘要视图。
     */
    private EpisodeSummaryView view(Long episodeId, String source, List<String> flags) {
        return new EpisodeSummaryView(episodeId, 5001L, 1, "第1集 开始", "测试短剧",
                "摘要", source, !flags.isEmpty(), flags);
    }
}
