package com.msd.content.companion;

import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.dto.SummaryClaimDTO;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCompanionClient;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.EpisodeSummaryView;
import com.msd.content.index.EpisodeIndexService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分集摘要读取、生成和降级测试。
 */
class EpisodeSummaryServiceTest {

    private final DramaCompanionClient dramaClient = mock(DramaCompanionClient.class);
    private final CompanionLightModel lightModel = mock(CompanionLightModel.class);
    private final EpisodeIndexService indexService = mock(EpisodeIndexService.class);
    private final CompanionProperties properties = new CompanionProperties();
    private final CompanionAccessService accessService = mock(CompanionAccessService.class);

    private final EpisodeSummaryService service = new EpisodeSummaryService(
            dramaClient, lightModel, indexService, properties, accessService);

    /**
     * 已有摘要直接返回，不认领也不调用模型。
     */
    @Test
    void returnsSeededSummaryWithoutClaim() {
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(meta("已有摘要")));

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.summary()).isEqualTo("已有摘要");
        assertThat(view.source()).isEqualTo(EpisodeSummaryService.SOURCE_SEED);
        assertThat(view.degraded()).isFalse();
        verify(dramaClient, never()).claimSummary(6401L);
        verify(lightModel, never()).complete(any(), any());
    }

    /**
     * 付费集未解锁时摘要工具返回 locked，不生成也不回写。
     */
    @Test
    void returnsLockedSummaryForPaidEpisodeWithoutAccess() {
        CompanionEpisodeDTO meta = meta("已有摘要");
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(meta));
        when(accessService.canAccess(1003L, meta)).thenReturn(false);

        EpisodeSummaryView view = service.getSummaryForUser(6401L, 1003L);

        assertThat(view.summary()).isEmpty();
        assertThat(view.source()).isEqualTo(EpisodeSummaryService.SOURCE_LOCKED);
        assertThat(view.degraded()).isTrue();
        assertThat(view.degradedFlags()).containsExactly("locked");
        verify(dramaClient, never()).claimSummary(6401L);
        verify(lightModel, never()).complete(any(), any());
    }

    /**
     * 认领成功后生成摘要、回写并更新单集索引。
     */
    @Test
    void generatesWritesBackAndIndexes() {
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(meta("")));
        when(dramaClient.claimSummary(6401L))
                .thenReturn(Result.ok(new SummaryClaimDTO(true, "", 1)));
        when(lightModel.isAvailable()).thenReturn(true);
        when(lightModel.complete(any(), any())).thenReturn("生成的摘要");
        when(lightModel.getModelName()).thenReturn("qwen-turbo");
        when(dramaClient.writebackSummary(eq(6401L), any())).thenReturn(Result.ok(true));
        when(indexService.upsert(6401L, 5005L, "长安第一女仵作", 1,
                "第1集 雨夜尸案", "生成的摘要", 0)).thenReturn(true);

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.summary()).isEqualTo("生成的摘要");
        assertThat(view.source()).isEqualTo(EpisodeSummaryService.SOURCE_GENERATED);
        assertThat(view.degraded()).isFalse();
        verify(indexService).upsert(6401L, 5005L, "长安第一女仵作", 1,
                "第1集 雨夜尸案", "生成的摘要", 0);
    }

    /**
     * 认领失败但已有摘要时直接返回其他实例生成的结果。
     */
    @Test
    void returnsExistingWhenClaimLoses() {
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(meta("")));
        when(dramaClient.claimSummary(6401L))
                .thenReturn(Result.ok(new SummaryClaimDTO(false, "其他实例摘要", 2)));

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.summary()).isEqualTo("其他实例摘要");
        assertThat(view.source()).isEqualTo(EpisodeSummaryService.SOURCE_SEED);
        verify(lightModel, never()).complete(any(), any());
    }

    /**
     * 认领失败且仍为空时标记生成中，不调用模型。
     */
    @Test
    void marksGeneratingWhenClaimLosesWithoutSummary() {
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(meta("")));
        when(dramaClient.claimSummary(6401L))
                .thenReturn(Result.ok(new SummaryClaimDTO(false, "", 1)));

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.summary()).isEmpty();
        assertThat(view.source()).isEqualTo(EpisodeSummaryService.SOURCE_GENERATING);
        assertThat(view.degradedFlags())
                .containsExactly(EpisodeSummaryService.FLAG_GENERATING);
        verify(lightModel, never()).complete(any(), any());
    }

    /**
     * 轻量模型未配置时释放认领，不等待模型超时。
     */
    @Test
    void degradesWhenModelUnavailableAndReleases() {
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(meta("")));
        when(dramaClient.claimSummary(6401L))
                .thenReturn(Result.ok(new SummaryClaimDTO(true, "", 1)));
        when(lightModel.isAvailable()).thenReturn(false);

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.source()).isEqualTo(EpisodeSummaryService.SOURCE_MISSING);
        assertThat(view.degradedFlags()).containsExactly(EpisodeSummaryService.FLAG_MODEL);
        verify(dramaClient).releaseSummaryClaim(6401L);
        verify(lightModel, never()).complete(any(), any());
    }

    /**
     * 生成失败时释放认领，允许后续重试。
     */
    @Test
    void releasesWhenGenerationFails() {
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(meta("")));
        when(dramaClient.claimSummary(6401L))
                .thenReturn(Result.ok(new SummaryClaimDTO(true, "", 1)));
        when(lightModel.isAvailable()).thenReturn(true);
        when(lightModel.complete(any(), any())).thenReturn(null);

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.source()).isEqualTo(EpisodeSummaryService.SOURCE_MISSING);
        assertThat(view.degradedFlags())
                .containsExactly(EpisodeSummaryService.FLAG_GENERATION);
        verify(dramaClient).releaseSummaryClaim(6401L);
    }

    /**
     * 回写失败时仍返回生成结果并标记降级。
     */
    @Test
    void keepsGeneratedSummaryWhenWritebackFails() {
        when(dramaClient.companion(6401L))
                .thenReturn(Result.ok(meta("")))
                .thenReturn(Result.ok(meta("")));
        when(dramaClient.claimSummary(6401L))
                .thenReturn(Result.ok(new SummaryClaimDTO(true, "", 1)));
        when(lightModel.isAvailable()).thenReturn(true);
        when(lightModel.complete(any(), any())).thenReturn("生成的摘要");
        when(lightModel.getModelName()).thenReturn("qwen-turbo");
        when(dramaClient.writebackSummary(eq(6401L), any())).thenReturn(Result.ok(false));

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.summary()).isEqualTo("生成的摘要");
        assertThat(view.degradedFlags())
                .containsExactly(EpisodeSummaryService.FLAG_WRITEBACK);
        verify(dramaClient).releaseSummaryClaim(6401L);
        verify(indexService, never()).upsert(any(), any(), any(), any(), any(), any());
    }

    /**
     * 索引更新失败不影响摘要返回。
     */
    @Test
    void marksIndexFailure() {
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(meta("")));
        when(dramaClient.claimSummary(6401L))
                .thenReturn(Result.ok(new SummaryClaimDTO(true, "", 1)));
        when(lightModel.isAvailable()).thenReturn(true);
        when(lightModel.complete(any(), any())).thenReturn("生成的摘要");
        when(lightModel.getModelName()).thenReturn("qwen-turbo");
        when(dramaClient.writebackSummary(eq(6401L), any())).thenReturn(Result.ok(true));
        when(indexService.upsert(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.summary()).isEqualTo("生成的摘要");
        assertThat(view.degradedFlags())
                .containsExactly(EpisodeSummaryService.FLAG_INDEX);
    }

    /**
     * 超长摘要在句末标点处截断，避免从句子中间断开。
     */
    @Test
    void truncatesGeneratedSummaryAtSentenceBoundary() {
        properties.getSummary().setMaxChars(20);
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(meta("")));
        when(dramaClient.claimSummary(6401L))
                .thenReturn(Result.ok(new SummaryClaimDTO(true, "", 1)));
        when(lightModel.isAvailable()).thenReturn(true);
        when(lightModel.complete(any(), any()))
                .thenReturn("第一句完整。第二句也很完整。第三句超出上限。");
        when(lightModel.getModelName()).thenReturn("qwen-flash");
        when(dramaClient.writebackSummary(eq(6401L), any())).thenReturn(Result.ok(true));
        when(indexService.upsert(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.summary()).isEqualTo("第一句完整。第二句也很完整。");
        assertThat(view.summary().length()).isLessThanOrEqualTo(20);
    }

    /**
     * 剧集不可见时返回 null。
     */
    @Test
    void returnsNullWhenEpisodeNotVisible() {
        when(dramaClient.companion(6401L)).thenReturn(Result.ok(null));

        assertThat(service.getSummary(6401L)).isNull();
    }

    /**
     * drama 不可用时返回降级视图，不编造剧集信息。
     */
    @Test
    void degradesWhenDramaUnavailable() {
        when(dramaClient.companion(6401L)).thenThrow(new IllegalStateException("drama down"));

        EpisodeSummaryView view = service.getSummary(6401L);

        assertThat(view.episodeId()).isEqualTo(6401L);
        assertThat(view.summary()).isEmpty();
        assertThat(view.degraded()).isTrue();
        assertThat(view.degradedFlags()).containsExactly(EpisodeSummaryService.FLAG_DRAMA);
    }

    /**
     * 构造剧集元信息。
     */
    private CompanionEpisodeDTO meta(String summary) {
        return new CompanionEpisodeDTO(
                6401L, 5005L, "长安第一女仵作", 1, "第1集 雨夜尸案",
                summary, summary.isEmpty() ? 0 : 2, "长安城接连出现离奇命案。");
    }
}
