package com.msd.content.index;

import com.msd.common.dto.EpisodeTranscriptDTO;
import com.msd.common.dto.TranscriptSegmentDTO;
import com.msd.content.rag.RagMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字幕 Document 构建测试。
 */
class TranscriptDocumentBuilderTest {

    private final TranscriptDocumentBuilder builder = new TranscriptDocumentBuilder();

    /**
     * 文档 ID、正文和 metadata 都包含剧集和时间轴。
     */
    @Test
    void buildsTranscriptDocument() {
        EpisodeTranscriptDTO transcript = new EpisodeTranscriptDTO(
                5005L, "长安第一女仵作", 6005L, 3, "第3集 雨夜尸案", 0,
                List.of(new TranscriptSegmentDTO(1L, 75_000, 79_000, "沈昭在雨夜发现尸体")));

        Document document = builder.build(transcript, transcript.segments().get(0));

        assertThat(document.getId()).isEqualTo("transcript-6005-75000");
        assertThat(document.getText())
                .contains("剧名：长安第一女仵作")
                .contains("第3集 雨夜尸案")
                .contains("时间：01:15-01:19")
                .contains("字幕：沈昭在雨夜发现尸体");
        assertThat(document.getMetadata())
                .containsEntry(RagMetadata.DOC_TYPE, RagMetadata.DOC_TYPE_TRANSCRIPT)
                .containsEntry(RagMetadata.DRAMA_ID, 5005L)
                .containsEntry(RagMetadata.EPISODE_ID, 6005L)
                .containsEntry(RagMetadata.EPISODE_NO, 3)
                .containsEntry(RagMetadata.START_MS, 75_000)
                .containsEntry(RagMetadata.END_MS, 79_000)
                .containsEntry(RagMetadata.TRANSCRIPT, "沈昭在雨夜发现尸体");
    }

    /**
     * 毫秒转 mm:ss。
     */
    @Test
    void formatsTimestamp() {
        assertThat(builder.timestamp(0)).isEqualTo("00:00");
        assertThat(builder.timestamp(61_000)).isEqualTo("01:01");
        assertThat(builder.timestamp(3_723_000)).isEqualTo("62:03");
    }
}
