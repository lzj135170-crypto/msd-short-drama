package com.msd.content.index;

import com.msd.common.dto.DramaRagExportDTO;
import com.msd.common.dto.EpisodeRagExportDTO;
import com.msd.content.config.RagProperties;
import com.msd.content.rag.RagMetadata;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把短剧导出数据转换为向量库和 BM25 共用的 Document。
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DramaDocumentBuilder {

    /** 来源摘要最大字符数。 */
    private static final int SNIPPET_CHARS = 120;

    // 文档裁剪配置
    private final RagProperties properties;

    /**
     * 注入文档裁剪配置。
     */
    public DramaDocumentBuilder(RagProperties properties) {
        this.properties = properties;
    }

    /**
     * 构建带稳定 ID 和业务 metadata 的文档。
     */
    public Document build(DramaRagExportDTO drama) {
        // 1. 文档 ID 固定为 drama-{id}，保证 Milvus 和 ES 可以使用同一套去重键
        String documentId = "drama-" + drama.id();

        // 2. metadata 保留推荐来源需要的展示字段和计数
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_DRAMA);
        metadata.put(RagMetadata.DRAMA_ID, drama.id());
        metadata.put(RagMetadata.TITLE, nullToEmpty(drama.title()));
        metadata.put(RagMetadata.SUBTITLE, nullToEmpty(drama.subtitle()));
        metadata.put(RagMetadata.COVER_URL, nullToEmpty(drama.coverUrl()));
        metadata.put(RagMetadata.CATEGORY_ID, drama.categoryId() == null ? 0L : drama.categoryId());
        metadata.put(RagMetadata.CATEGORY_NAME, nullToEmpty(drama.categoryName()));
        metadata.put(RagMetadata.TAGS, drama.tags() == null ? List.of() : drama.tags());
        metadata.put(RagMetadata.ACTORS, drama.actors() == null ? List.of() : drama.actors());
        metadata.put(RagMetadata.EPISODE_COUNT, drama.episodeCount() == null ? 0 : drama.episodeCount());
        metadata.put(RagMetadata.VIEW_COUNT, drama.viewCount() == null ? 0L : drama.viewCount());
        metadata.put(RagMetadata.LIKE_COUNT, drama.likeCount() == null ? 0L : drama.likeCount());
        metadata.put(RagMetadata.PUBLISH_TIME, nullToEmpty(drama.publishTime()));
        metadata.put(RagMetadata.SNIPPET, snippet(drama));

        // 3. 正文用于 embedding，字段顺序固定，便于排查
        return Document.builder()
                .id(documentId)
                .text(buildText(drama))
                .metadata(metadata)
                .build();
    }

    /**
     * 构建分集文档，供 AI 伴看检索当前集之前的前情。
     */
    public Document buildEpisode(DramaRagExportDTO drama, EpisodeRagExportDTO episode) {
        return buildEpisode(drama.id(), drama.title(),
                episode.id(), episode.episodeNo(), episode.title(), episode.summary(),
                episode.pricePoints());
    }

    /**
     * 按字段构建分集文档，供摘要回写后的单集索引更新复用。
     */
    public Document buildEpisode(Long dramaId, String dramaTitle, Long episodeId, Integer episodeNo,
                                 String episodeTitle, String summary) {
        return buildEpisode(dramaId, dramaTitle, episodeId, episodeNo,
                episodeTitle, summary, 0);
    }

    /**
     * 按字段构建分集文档，并把价格写入 metadata 供前情权限过滤。
     */
    public Document buildEpisode(Long dramaId, String dramaTitle, Long episodeId, Integer episodeNo,
                                 String episodeTitle, String summary, Integer pricePoints) {
        // 1. 文档 ID 固定为 episode-{id}，和短剧文档区分开
        String documentId = "episode-" + episodeId;
        String cleanSummary = nullToEmpty(summary);

        // 2. metadata 同时保留短剧归属、集数、摘要和价格，检索时用于过滤与展示
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_EPISODE);
        metadata.put(RagMetadata.DRAMA_ID, dramaId);
        metadata.put(RagMetadata.DRAMA_TITLE, nullToEmpty(dramaTitle));
        metadata.put(RagMetadata.EPISODE_ID, episodeId);
        metadata.put(RagMetadata.EPISODE_NO, episodeNo == null ? 0 : episodeNo);
        metadata.put(RagMetadata.EPISODE_TITLE, nullToEmpty(episodeTitle));
        metadata.put(RagMetadata.SUMMARY, cleanSummary);
        metadata.put(RagMetadata.PRICE_POINTS,
                pricePoints == null ? 0 : pricePoints);
        metadata.put(RagMetadata.SNIPPET, truncate(cleanSummary, SNIPPET_CHARS));

        // 3. 正文用于 embedding，剧名和摘要提供语义匹配依据
        return Document.builder()
                .id(documentId)
                .text(buildEpisodeText(dramaTitle, episodeNo, episodeTitle, cleanSummary))
                .metadata(metadata)
                .build();
    }

    /**
     * 拼接用于 embedding 的分集文本，空字段自动跳过。
     */
    public String buildEpisodeText(String dramaTitle, Integer episodeNo,
                                   String episodeTitle, String summary) {
        StringBuilder text = new StringBuilder();
        appendLine(text, "剧名", dramaTitle);
        if (episodeNo != null) {
            String prefix = "第" + episodeNo + "集";
            String title = nullToEmpty(episodeTitle).trim();
            // 标题已带“第N集”前缀时不再重复拼接
            String line = title.isEmpty()
                    ? prefix
                    : (title.startsWith(prefix) ? title : prefix + " " + title);
            appendLine(text, "分集", line);
        }
        appendLine(text, "摘要", summary);
        return text.toString().trim();
    }

    /**
     * 拼接用于 embedding 的短剧文本。
     */
    public String buildText(DramaRagExportDTO drama) {
        StringBuilder text = new StringBuilder();
        appendLine(text, "标题", drama.title());
        appendLine(text, "副标题", drama.subtitle());
        appendLine(text, "分类", drama.categoryName());
        appendLine(text, "标签", join(drama.tags()));
        appendLine(text, "演员", join(drama.actors()));
        appendLine(text, "简介", truncate(nullToEmpty(drama.description()),
                properties.getDocument().getMaxDescriptionChars()));
        appendLine(text, "剧集", episodeLine(drama.episodeTitles()));
        return text.toString().trim();
    }

    /**
     * 生成来源卡片使用的短摘要。
     */
    public String snippet(DramaRagExportDTO drama) {
        // 1. 优先用简介，保证推荐理由有可读依据
        String description = nullToEmpty(drama.description()).trim();
        if (!description.isEmpty()) {
            return truncate(description, SNIPPET_CHARS);
        }
        // 2. 没有简介时退回副标题，再退回标题
        String subtitle = nullToEmpty(drama.subtitle()).trim();
        if (!subtitle.isEmpty()) {
            return truncate(subtitle, SNIPPET_CHARS);
        }
        return nullToEmpty(drama.title());
    }

    /**
     * 追加一行“字段：值”，空值跳过。
     */
    private void appendLine(StringBuilder text, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        text.append(label).append('：').append(value.trim()).append('\n');
    }

    /**
     * 拼接列表，空列表返回空字符串。
     */
    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("、", values);
    }

    /**
     * 剧集标题按配置数量截断，避免文档过长稀释 embedding。
     */
    private String episodeLine(List<String> episodeTitles) {
        if (episodeTitles == null || episodeTitles.isEmpty()) {
            return "";
        }
        int limit = Math.max(properties.getDocument().getMaxEpisodeTitles(), 1);
        return String.join("；", episodeTitles.stream().limit(limit).toList());
    }

    /**
     * 按字符数截断，null 安全。
     */
    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int limit = Math.max(maxChars, 1);
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * null 转空字符串。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
