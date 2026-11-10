package com.msd.drama.service;

import com.msd.common.constant.EpisodeSummaryStatus;
import com.msd.common.dto.CompanionEpisodeDTO;
import com.msd.common.dto.SummaryClaimDTO;
import com.msd.common.dto.SummaryWritebackRequest;
import com.msd.common.exception.BizException;
import com.msd.drama.dto.CompanionEpisodeRow;
import com.msd.drama.mapper.EpisodeMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 伴看剧集服务测试。
 */
class CompanionEpisodeServiceTest {

    private final EpisodeMapper episodeMapper = mock(EpisodeMapper.class);
    private final CompanionEpisodeService service = new CompanionEpisodeService(episodeMapper);

    /**
     * 剧集不可见时返回 null，不暴露任何元信息。
     */
    @Test
    void returnsNullWhenEpisodeIsNotVisible() {
        when(episodeMapper.selectCompanionEpisodeById(6001L)).thenReturn(null);

        assertThat(service.getCompanionEpisode(6001L)).isNull();
    }

    /**
     * 转换时归一空摘要，并把简介截断到 500 字。
     */
    @Test
    void mapsCompanionMetaAndTruncatesDescription() {
        CompanionEpisodeRow row = row();
        row.setSummary(null);
        row.setDramaDescription("简".repeat(600));
        row.setPricePoints(30);
        when(episodeMapper.selectCompanionEpisodeById(6001L)).thenReturn(row);

        CompanionEpisodeDTO dto = service.getCompanionEpisode(6001L);

        assertThat(dto.episodeId()).isEqualTo(6001L);
        assertThat(dto.dramaId()).isEqualTo(5001L);
        assertThat(dto.dramaTitle()).isEqualTo("闪婚总裁");
        assertThat(dto.episodeNo()).isEqualTo(1);
        assertThat(dto.episodeTitle()).isEqualTo("第1集 闪婚");
        assertThat(dto.summary()).isEmpty();
        assertThat(dto.summaryStatus()).isZero();
        assertThat(dto.dramaDescription()).hasSize(500);
        assertThat(dto.pricePoints()).isEqualTo(30);
    }

    /**
     * 已有摘要时不认领，直接返回现有内容。
     */
    @Test
    void returnsExistingSummaryWithoutClaim() {
        CompanionEpisodeRow row = row();
        row.setSummary("苏晚与顾沉舟闪婚。");
        row.setSummaryStatus(EpisodeSummaryStatus.READY);
        when(episodeMapper.selectCompanionEpisodeById(6001L)).thenReturn(row);

        SummaryClaimDTO claim = service.claimSummary(6001L);

        assertThat(claim.claimed()).isFalse();
        assertThat(claim.summary()).isEqualTo("苏晚与顾沉舟闪婚。");
        assertThat(claim.summaryStatus()).isEqualTo(EpisodeSummaryStatus.READY);
        verify(episodeMapper, never()).claimSummary(6001L);
    }

    /**
     * 空摘要时条件更新成功，调用方获得生成权。
     */
    @Test
    void claimsMissingSummary() {
        when(episodeMapper.selectCompanionEpisodeById(6001L)).thenReturn(row());
        when(episodeMapper.claimSummary(6001L)).thenReturn(1);

        SummaryClaimDTO claim = service.claimSummary(6001L);

        assertThat(claim.claimed()).isTrue();
        assertThat(claim.summary()).isEmpty();
        assertThat(claim.summaryStatus()).isEqualTo(EpisodeSummaryStatus.GENERATING);
    }

    /**
     * 认领失败时重新读取，其他实例已生成的摘要可以直接使用。
     */
    @Test
    void returnsLatestStateWhenClaimLoses() {
        CompanionEpisodeRow generating = row();
        CompanionEpisodeRow ready = row();
        ready.setSummary("其他实例生成的摘要。");
        ready.setSummaryStatus(EpisodeSummaryStatus.READY);
        when(episodeMapper.selectCompanionEpisodeById(6001L))
                .thenReturn(generating)
                .thenReturn(ready);
        when(episodeMapper.claimSummary(6001L)).thenReturn(0);

        SummaryClaimDTO claim = service.claimSummary(6001L);

        assertThat(claim.claimed()).isFalse();
        assertThat(claim.summary()).isEqualTo("其他实例生成的摘要。");
        assertThat(claim.summaryStatus()).isEqualTo(EpisodeSummaryStatus.READY);
    }

    /**
     * 回写前去掉首尾空白，并按 Mapper 结果返回是否落库。
     */
    @Test
    void writebackSummaryTrimsAndWrites() {
        when(episodeMapper.writebackSummary(6001L, "生成的摘要")).thenReturn(1);

        boolean written = service.writebackSummary(
                6001L, new SummaryWritebackRequest("  生成的摘要  ", "qwen-turbo"));

        assertThat(written).isTrue();
    }

    /**
     * 空白摘要直接拒绝，不触发数据库写入。
     */
    @Test
    void writebackRejectsBlankSummary() {
        assertThatThrownBy(() -> service.writebackSummary(
                6001L, new SummaryWritebackRequest("   ", "qwen-turbo")))
                .isInstanceOf(BizException.class);
        verify(episodeMapper, never()).writebackSummary(6001L, "");
    }

    /**
     * 释放认领返回 Mapper 的影响行数结果。
     */
    @Test
    void releaseReturnsMapperResult() {
        when(episodeMapper.releaseSummaryClaim(6001L)).thenReturn(1);

        assertThat(service.releaseSummaryClaim(6001L)).isTrue();
    }

    /**
     * 构造可认领的剧集查询行。
     */
    private CompanionEpisodeRow row() {
        CompanionEpisodeRow row = new CompanionEpisodeRow();
        row.setEpisodeId(6001L);
        row.setDramaId(5001L);
        row.setDramaTitle("闪婚总裁");
        row.setEpisodeNo(1);
        row.setEpisodeTitle("第1集 闪婚");
        row.setSummary("");
        row.setSummaryStatus(EpisodeSummaryStatus.MISSING);
        row.setDramaDescription("苏晚为保住家族企业，与顾沉舟闪婚。");
        return row;
    }
}
