package com.msd.content.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 提示模板测试。
 */
class RagConfigTest {

    private final ContextualQueryAugmenter augmenter = new RagConfig().contextualQueryAugmenter();

    /**
     * ContextualQueryAugmenter 对空上下文模板做无参渲染，模板不能包含占位符。
     */
    @Test
    void emptyContextTemplateRendersWithoutVariables() {
        Query query = Query.builder().text("有没有甜宠剧").build();

        Query augmented = augmenter.augment(query, List.of());

        assertThat(augmented.text())
                .contains("没有检索到匹配的短剧资料")
                .doesNotContain("{query}");
    }

    /**
     * 非空上下文模板应同时包含资料和原始查询。
     */
    @Test
    void contextTemplateContainsDocumentsAndQuery() {
        Query query = Query.builder().text("有没有甜宠剧").build();
        Document document = Document.builder()
                .id("drama-1")
                .text("标题：先婚后爱")
                .build();

        Query augmented = augmenter.augment(query, List.of(document));

        assertThat(augmented.text())
                .contains("【短剧资料】")
                .contains("标题：先婚后爱")
                .contains("【用户需求】")
                .contains("有没有甜宠剧");
    }
}
