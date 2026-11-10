package com.msd.content.index;

import com.msd.common.dto.EpisodeTranscriptDTO;
import com.msd.common.dto.TranscriptSegmentDTO;
import com.msd.content.rag.RagMetadata;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把字幕片段转换为 Milvus 和 ES 共用的 Document。
 */
@Component
public class TranscriptDocumentBuilder {

    /** 引用展示的最大字符数。 */
    private static final int SNIPPET_CHARS = 120;

    /**
     * 构建单条字幕文档，ID 按剧集和开始时间稳定生成。
     */
    public Document build(EpisodeTranscriptDTO transcript, TranscriptSegmentDTO segment) {
        String documentId = "transcript-" + transcript.episodeId() + "-" + segment.startMs();
        String text = segment.text() == null ? "" : segment.text().trim();

        // 1. metadata 保留短剧、剧集、时间轴和价格，供检索过滤和引用展示
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_TRANSCRIPT);
        metadata.put(RagMetadata.DRAMA_ID, transcript.dramaId());
        metadata.put(RagMetadata.DRAMA_TITLE, transcript.dramaTitle());
        metadata.put(RagMetadata.EPISODE_ID, transcript.episodeId());
        metadata.put(RagMetadata.EPISODE_NO, transcript.episodeNo());
        metadata.put(RagMetadata.EPISODE_TITLE, transcript.episodeTitle());
        metadata.put(RagMetadata.START_MS, segment.startMs());
        metadata.put(RagMetadata.END_MS, segment.endMs());
        metadata.put(RagMetadata.PRICE_POINTS, transcript.pricePoints());
        metadata.put(RagMetadata.TRANSCRIPT, text);
        metadata.put(RagMetadata.SNIPPET, truncate(text, SNIPPET_CHARS));

        // 2. 正文带上剧名、集数和时间戳，提升语义检索命中率
        String documentText = "剧名：" + transcript.dramaTitle()
                + "\n第" + transcript.episodeNo() + "集 " + transcript.episodeTitle()
                + "\n时间：" + timestamp(segment.startMs()) + "-" + timestamp(segment.endMs())
                + "\n字幕：" + text;
        return Document.builder()
                .id(documentId)
                .text(documentText)
                .metadata(metadata)
                .build();
    }

    /**
     * 毫秒转 mm:ss，供模型和前端引用。
     */
    public String timestamp(Integer millis) {
        int value = millis == null ? 0 : Math.max(millis, 0);
        return String.format("%02d:%02d", value / 60_000, (value % 60_000) / 1000);
    }

    /**
     * 截断长文本。
     */
    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
