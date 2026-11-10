package com.msd.content.companion;

import com.msd.common.dto.RecapEpisodeDTO;
import com.msd.common.result.Result;
import com.msd.content.client.DramaCatalogClient;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.RecapItem;
import com.msd.content.dto.RecapView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 前情提要/本集看点测试。
 */
class CompanionRecapServiceTest {

    private final DramaCatalogClient catalogClient = mock(DramaCatalogClient.class);
    private final CompanionAccessService accessService = mock(CompanionAccessService.class);
    private final CompanionRecapService service = new CompanionRecapService(
            catalogClient, accessService, new CompanionProperties());

    /**
     * 只返回当前集之前且已解锁的分集，未来集必须被过滤。
     */
    @Test
    void returnsOnlyPreviousUnlockedEpisodes() {
        when(catalogClient.recap(5001L, 5)).thenReturn(Result.ok(List.of(
                new RecapEpisodeDTO(6001L, 1, "第1集 相遇", "", 0, 0),
                new RecapEpisodeDTO(6002L, 2, "第2集 危机", "第二集摘要", 30, 2),
                new RecapEpisodeDTO(6003L, 3, "第3集 反击", "第三集摘要", 0, 2),
                new RecapEpisodeDTO(6005L, 5, "第5集 告白", "第五集摘要", 0, 2),
                new RecapEpisodeDTO(6006L, 6, "第6集 未来", "未来摘要", 0, 2))));
        when(accessService.unlockedEpisodeIds(1003L, 5001L)).thenReturn(Set.of());

        RecapView view = service.getRecap(5001L, 5, 1003L);

        assertThat(view.current()).extracting(RecapItem::episodeNo).isEqualTo(5);
        assertThat(view.previous())
                .extracting(RecapItem::episodeNo)
                .containsExactly(3);
        assertThat(view.degradedFlags()).contains("summary_missing");
    }

    /**
     * 当前集付费且未解锁时只返回前情，并标记 locked。
     */
    @Test
    void hidesLockedCurrentEpisode() {
        when(catalogClient.recap(5001L, 5)).thenReturn(Result.ok(List.of(
                new RecapEpisodeDTO(6003L, 3, "第3集 反击", "第三集摘要", 0, 2),
                new RecapEpisodeDTO(6005L, 5, "第5集 告白", "第五集摘要", 30, 2))));
        when(accessService.unlockedEpisodeIds(1003L, 5001L)).thenReturn(Set.of());

        RecapView view = service.getRecap(5001L, 5, 1003L);

        assertThat(view.current()).isNull();
        assertThat(view.previous()).extracting(RecapItem::episodeNo).containsExactly(3);
        assertThat(view.degradedFlags()).contains("locked");
    }

    /**
     * drama 不可用时返回降级空结果。
     */
    @Test
    void degradesWhenDramaUnavailable() {
        when(catalogClient.recap(5001L, 5)).thenThrow(new IllegalStateException("drama down"));

        RecapView view = service.getRecap(5001L, 5, 1003L);

        assertThat(view.current()).isNull();
        assertThat(view.previous()).isEmpty();
        assertThat(view.degraded()).isTrue();
        assertThat(view.degradedFlags()).containsExactly("drama");
    }
}
