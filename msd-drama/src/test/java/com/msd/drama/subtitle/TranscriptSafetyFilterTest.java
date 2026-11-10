package com.msd.drama.subtitle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字幕安全过滤测试。
 */
class TranscriptSafetyFilterTest {

    private final TranscriptSafetyFilter filter = new TranscriptSafetyFilter();

    /**
     * 联系方式掩码但保留剧情文本。
     */
    @Test
    void masksContactInfo() {
        String text = filter.sanitize("打我电话 13800138000 聊剧情");

        assertThat(text).contains("聊剧情").doesNotContain("13800138000");
    }

    /**
     * 明显引流话术整条丢弃。
     */
    @Test
    void dropsSolicitation() {
        assertThat(filter.sanitize("加微信领取全集")).isNull();
    }

    /**
     * 空白和超长文本直接丢弃。
     */
    @Test
    void dropsBlankAndOverlongText() {
        assertThat(filter.sanitize(" ")).isNull();
        assertThat(filter.sanitize("字".repeat(1001))).isNull();
    }
}
