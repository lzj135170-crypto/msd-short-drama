package com.msd.content.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 输出轻量过滤测试。
 */
class AiOutputGuardTest {

    private final AiOutputGuard guard = new AiOutputGuard();

    /**
     * 手机号、邮箱和身份证号应被隐藏。
     */
    @Test
    void hidesContactAndPii() {
        String text = "联系我 13800138000，邮箱 test@example.com，身份证 110101199001011234。";

        String sanitized = guard.sanitize(text, 1000);

        assertThat(sanitized)
                .doesNotContain("13800138000")
                .doesNotContain("test@example.com")
                .doesNotContain("110101199001011234")
                .contains("[联系方式已隐藏]");
    }

    /**
     * 明显引流/诈骗话术应被隐藏。
     */
    @Test
    void hidesSensitiveSolicitation() {
        String sanitized = guard.sanitize("想赚钱可以刷单，或者私下转账。", 1000);

        assertThat(sanitized)
                .doesNotContain("刷单")
                .doesNotContain("私下转账")
                .contains("[内容已安全处理]");
    }

    /**
     * 超长回答应截断，避免异常输出撑大响应。
     */
    @Test
    void truncatesOverlongReply() {
        String sanitized = guard.sanitize("剧".repeat(50), 10);

        assertThat(sanitized).hasSize(11).endsWith("…");
    }

    /**
     * 普通回答保持原样。
     */
    @Test
    void keepsNormalReply() {
        assertThat(guard.sanitize("顾沉舟在第5集告白了。", 1000))
                .isEqualTo("顾沉舟在第5集告白了。");
    }
}
