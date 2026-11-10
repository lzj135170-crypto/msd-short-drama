package com.msd.content.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中文 BM25 分词器测试。
 */
class ChineseTokenizerTest {

    private final ChineseTokenizer tokenizer = new ChineseTokenizer();

    /**
     * 查询侧应过滤口语停用词，保留题材和情绪词。
     */
    @Test
    void queryTokenizationFiltersStopWords() {
        List<String> tokens = tokenizer.tokenizeForQuery("有没有那种先婚后爱的甜宠剧");

        assertThat(tokens).isNotEmpty();
        assertThat(tokens).doesNotContain("有没有", "那种", "的", "剧");
        assertThat(tokens).anyMatch(token -> token.contains("甜"));
        assertThat(tokens).anyMatch(token -> token.contains("婚"));
    }

    /**
     * 索引侧保留停用词，保证和查询侧使用同一套词元。
     */
    @Test
    void indexTokenizationKeepsStopWords() {
        List<String> tokens = tokenizer.tokenizeForIndex("隐婚总裁的甜妻");

        assertThat(tokens).contains("的");
        assertThat(tokens).anyMatch(token -> token.contains("总裁"));
    }

    /**
     * 英文和数字归一化为小写后保留。
     */
    @Test
    void keepsEnglishAndNumbers() {
        List<String> tokens = tokenizer.tokenizeForQuery("AI 2026 穿越");

        assertThat(tokens).contains("ai", "2026", "穿越");
    }

    /**
     * 纯标点不应产生 token。
     */
    @Test
    void ignoresPunctuationOnlyText() {
        assertThat(tokenizer.tokenizeForQuery("，。！？；：")).isEmpty();
    }

    /**
     * 索引拼接结果使用空格分隔。
     */
    @Test
    void joinsIndexTokensWithSpace() {
        String joined = tokenizer.joinForIndex("长安第一女仵作");

        assertThat(joined).isNotBlank();
        assertThat(joined).doesNotContain("  ");
        assertThat(joined.split(" ")).hasSizeGreaterThan(1);
    }
}
