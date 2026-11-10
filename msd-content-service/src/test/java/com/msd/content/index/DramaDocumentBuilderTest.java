package com.msd.content.index;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.content.config.RagProperties;
import com.msd.content.rag.RagMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 短剧 Document 构建测试。
 */
class DramaDocumentBuilderTest {

    /**
     * 标题、分类、标签、演员和剧集都应进入 embedding 文本和 metadata。
     */
    @Test
    void buildsTextAndMetadata() {
        RagProperties properties = new RagProperties();
        DramaDocumentBuilder builder = new DramaDocumentBuilder(properties);

        Document document = builder.build(drama());

        assertThat(document.getId()).isEqualTo("drama-5005");
        assertThat(document.getText())
                .contains("标题：长安第一女仵作")
                .contains("分类：古装悬疑")
                .contains("标签：悬疑、破案")
                .contains("演员：林一、苏晚")
                .contains("简介：长安城接连出现离奇命案")
                .contains("剧集：第1集 验尸；第2集 追凶");
        assertThat(document.getMetadata())
                .containsEntry(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_DRAMA)
                .containsEntry(RagMetadata.DRAMA_ID, 5005L)
                .containsEntry(RagMetadata.TITLE, "长安第一女仵作")
                .containsEntry(RagMetadata.CATEGORY_NAME, "古装悬疑")
                .containsEntry(RagMetadata.TAGS, List.of("悬疑", "破案"))
                .containsEntry(RagMetadata.ACTORS, List.of("林一", "苏晚"));
    }

    /**
     * 分集文档使用 episode-{id}，正文和 metadata 携带本集摘要。
     */
    @Test
    void buildsEpisodeDocument() {
        RagProperties properties = new RagProperties();
        DramaDocumentBuilder builder = new DramaDocumentBuilder(properties);
        EpisodeRagExportDTO episode = new EpisodeRagExportDTO(
                6401L, 1, "第1集 雨夜尸案", "长安雨夜发现一具无名女尸，沈昭被召入大理寺验尸。");

        Document document = builder.buildEpisode(drama(), episode);

        assertThat(document.getId()).isEqualTo("episode-6401");
        assertThat(document.getText())
                .contains("剧名：长安第一女仵作")
                .contains("分集：第1集 雨夜尸案")
                .contains("摘要：长安雨夜发现一具无名女尸");
        assertThat(document.getMetadata())
                .containsEntry(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_EPISODE)
                .containsEntry(RagMetadata.DRAMA_ID, 5005L)
                .containsEntry(RagMetadata.DRAMA_TITLE, "长安第一女仵作")
                .containsEntry(RagMetadata.EPISODE_ID, 6401L)
                .containsEntry(RagMetadata.EPISODE_NO, 1)
                .containsEntry(RagMetadata.EPISODE_TITLE, "第1集 雨夜尸案")
                .containsEntry(RagMetadata.SUMMARY, "长安雨夜发现一具无名女尸，沈昭被召入大理寺验尸。")
                .containsEntry(RagMetadata.SNIPPET, "长安雨夜发现一具无名女尸，沈昭被召入大理寺验尸。");
    }

    /**
     * 分集价格写入 metadata，供伴看前情过滤付费集。
     */
    @Test
    void writesPricePointsToEpisodeMetadata() {
        DramaDocumentBuilder builder = new DramaDocumentBuilder(new RagProperties());
        EpisodeRagExportDTO episode = new EpisodeRagExportDTO(
                6401L, 1, "第1集 雨夜尸案", "摘要", 30);

        Document document = builder.buildEpisode(drama(), episode);

        assertThat(document.getMetadata())
                .containsEntry(RagMetadata.PRICE_POINTS, 30);
    }

    /**
     * 分集标题没有“第N集”前缀时自动补全，已有前缀时不重复。
     */
    @Test
    void formatsEpisodeTitleWithoutDuplication() {
        DramaDocumentBuilder builder = new DramaDocumentBuilder(new RagProperties());

        assertThat(builder.buildEpisodeText("长安", 2, "追凶", "摘要"))
                .contains("分集：第2集 追凶");
        assertThat(builder.buildEpisodeText("长安", 2, "第2集 追凶", "摘要"))
                .contains("分集：第2集 追凶")
                .doesNotContain("第2集 第2集");
    }

    /**
     * 简介和剧集标题按配置裁剪，避免文档无限增长。
     */
    @Test
    void truncatesDescriptionAndEpisodes() {
        RagProperties properties = new RagProperties();
        properties.getDocument().setMaxDescriptionChars(10);
        properties.getDocument().setMaxEpisodeTitles(1);
        DramaDocumentBuilder builder = new DramaDocumentBuilder(properties);

        Document document = builder.build(drama());

        assertThat(document.getText())
                .contains("简介：长安城接连出现离奇")
                .doesNotContain("命案……")
                .contains("剧集：第1集 验尸")
                .doesNotContain("第2集 追凶");
    }

    /**
     * 构造一部测试短剧。
     */
    private DramaRagExportDTO drama() {
        return new DramaRagExportDTO(
                5005L,
                "长安第一女仵作",
                "验尸破案，顺便谈个恋爱",
                "http://127.0.0.1:9000/msd/covers/5005.jpg",
                "长安城接连出现离奇命案，女仵作沈知意验尸破案，逐渐揭开身世之谜。",
                3L,
                "古装悬疑",
                List.of("悬疑", "破案"),
                List.of("林一", "苏晚"),
                2,
                List.of("第1集 验尸", "第2集 追凶"),
                "2026-01-01T10:00:00",
                1000L,
                100L,
                50L,
                20L);
    }
}
