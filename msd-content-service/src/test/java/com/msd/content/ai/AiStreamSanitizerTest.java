package com.msd.content.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式输出过滤测试。
 */
class AiStreamSanitizerTest {

    /**
     * 手机号被拆到两个 chunk 时仍应完整隐藏。
     */
    @Test
    void masksContactSplitAcrossChunks() {
        AiStreamSanitizer sanitizer = new AiStreamSanitizer(new AiOutputGuard(), 1000);
        StringBuilder output = new StringBuilder();

        output.append(sanitizer.accept("前".repeat(70) + "联系我 1380"));
        output.append(sanitizer.accept("0138000 吧"));
        output.append(sanitizer.finish());

        assertThat(output.toString())
                .doesNotContain("13800138000")
                .contains("[联系方式已隐藏]");
    }

    /**
     * 超过上限后截断并补省略号。
     */
    @Test
    void truncatesAtLimit() {
        AiStreamSanitizer sanitizer = new AiStreamSanitizer(new AiOutputGuard(), 10);
        StringBuilder output = new StringBuilder();

        output.append(sanitizer.accept("剧".repeat(70)));
        output.append(sanitizer.finish());

        assertThat(output.toString()).hasSize(11).endsWith("…");
    }

    /**
     * 普通文本跨 chunk 拼接时不能被 trim 掉空格。
     */
    @Test
    void keepsNormalChunkSpacing() {
        AiStreamSanitizer sanitizer = new AiStreamSanitizer(new AiOutputGuard(), 1000);
        StringBuilder output = new StringBuilder();

        output.append(sanitizer.accept("顾沉舟 ".repeat(30)));
        output.append(sanitizer.accept("告白了。"));
        output.append(sanitizer.finish());

        assertThat(output.toString()).contains("顾沉舟 顾沉舟").contains("告白了。");
    }
}
