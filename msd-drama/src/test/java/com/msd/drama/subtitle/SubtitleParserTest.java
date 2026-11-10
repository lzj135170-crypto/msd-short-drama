package com.msd.drama.subtitle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SRT/VTT 字幕解析测试。
 */
class SubtitleParserTest {

    private final SubtitleParser parser = new SubtitleParser();

    /**
     * SRT 解析序号、逗号毫秒和多行文本。
     */
    @Test
    void parsesSrt() {
        String content = """
                1
                00:00:01,000 --> 00:00:03,500
                你好，欢迎来到码上短剧

                2
                00:00:04,000 --> 00:00:06,000
                第二句
                继续第二行
                """;

        List<SubtitleCue> cues = parser.parse("srt", content);

        assertThat(cues).hasSize(2);
        assertThat(cues.get(0).startMs()).isEqualTo(1000);
        assertThat(cues.get(0).endMs()).isEqualTo(3500);
        assertThat(cues.get(0).text()).isEqualTo("你好，欢迎来到码上短剧");
        assertThat(cues.get(1).text()).isEqualTo("第二句 继续第二行");
    }

    /**
     * VTT 支持 WEBVTT 头、点号毫秒和 cue id。
     */
    @Test
    void parsesVtt() {
        String content = """
                WEBVTT

                cue-1
                00:01.000 --> 00:03.000 align:start
                第一句

                00:04.000 --> 00:05.500
                <v 女主>第二句</v>
                """;

        List<SubtitleCue> cues = parser.parse("VTT", content);

        assertThat(cues).hasSize(2);
        assertThat(cues.get(0).startMs()).isEqualTo(1000);
        assertThat(cues.get(0).text()).isEqualTo("第一句");
        assertThat(cues.get(1).text()).isEqualTo("第二句");
    }

    /**
     * 无时间轴或结束早于开始的块直接跳过。
     */
    @Test
    void skipsMalformedBlocks() {
        String content = """
                这是一段没有时间轴的文本

                1
                00:00:03,000 --> 00:00:01,000
                反向时间
                """;

        assertThat(parser.parse("SRT", content)).isEmpty();
    }

    /**
     * 完全相同的时间轴和文本去重。
     */
    @Test
    void deduplicatesCues() {
        String content = """
                1
                00:00:01,000 --> 00:00:02,000
                重复

                2
                00:00:01,000 --> 00:00:02,000
                重复
                """;

        assertThat(parser.parse("SRT", content)).hasSize(1);
    }
}
