package com.msd.content.copilot;

import com.msd.common.exception.BizException;
import com.msd.content.ai.AiInputGuard;
import com.msd.content.ai.AiMetrics;
import com.msd.content.ai.AiOutputGuard;
import com.msd.content.ai.AiQuotaService;
import com.msd.content.config.CreatorCopilotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创作者 Copilot 生成、缓存、配额和降级测试。
 */
class CreatorCopilotServiceTest {

    private final CreatorCopilotProperties properties = new CreatorCopilotProperties();
    private final AiQuotaService quotaService = mock(AiQuotaService.class);

    /**
     * 成功生成多个版本，并清洗联系方式。
     */
    @Test
    void generatesSanitizedVersions() {
        ChatClient chatClient = chatClient(new CreatorCopilotSuggestion(
                List.of(version("闪婚后他宠我上瘾", "先婚后爱，甜到上头", "联系 13800138000"),
                        version("契约婚姻第三年", "一纸婚约，藏不住心动", "甜宠")),
                List.of("标题避免夸大")));
        CreatorCopilotService service = service(chatClient);
        allowQuota();

        CreatorCopilotResponse result = service.generate(1003L, request());

        assertThat(result.degraded()).isFalse();
        assertThat(result.versions()).hasSize(2);
        assertThat(result.versions().get(0).rationale())
                .doesNotContain("13800138000")
                .contains("[联系方式已隐藏]");
        assertThat(result.tips()).containsExactly("标题避免夸大");
        assertThat(result.requestId()).isNotBlank();
    }

    /**
     * 相同素材和参数命中缓存，不重复消耗配额和模型调用。
     */
    @Test
    void secondIdenticalRequestHitsCache() {
        ChatClient chatClient = chatClient(new CreatorCopilotSuggestion(
                List.of(version("标题", "副标题", "甜宠")), List.of()));
        CreatorCopilotService service = service(chatClient);
        allowQuota();
        clearInvocations(chatClient);

        CreatorCopilotResponse first = service.generate(1003L, request());
        clearInvocations(quotaService);
        CreatorCopilotResponse second = service.generate(1003L, request());

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.requestId()).isNotEqualTo(first.requestId());
        verify(chatClient, times(1)).prompt();
        verify(quotaService, never()).consume("creator_copilot", 1003L,
                properties.getDailyLimit());
    }

    /**
     * 每日配额超限时返回 429 语义异常。
     */
    @Test
    void rejectsWhenQuotaExceeded() {
        CreatorCopilotService service = service(chatClient(null));
        when(quotaService.consume("creator_copilot", 1003L, properties.getDailyLimit()))
                .thenReturn(new AiQuotaService.QuotaResult(false, 21, 0));

        assertThatThrownBy(() -> service.generate(1003L, request()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("次数已用完");
    }

    /**
     * 模型失败时返回空版本并标记降级，不编造文案。
     */
    @Test
    void degradesWhenModelFails() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString())
                .call().entity(CreatorCopilotSuggestion.class))
                .thenThrow(new IllegalStateException("llm down"));
        CreatorCopilotService service = service(chatClient);
        allowQuota();

        CreatorCopilotResponse result = service.generate(1003L, request());

        assertThat(result.versions()).isEmpty();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedFlags()).containsExactly("generation");
    }

    /**
     * 标题、简介全为空时拒绝生成。
     */
    @Test
    void rejectsEmptySource() {
        CreatorCopilotService service = service(chatClient(null));

        assertThatThrownBy(() -> service.generate(1003L,
                new CreatorCopilotRequest("", "", "", "", List.of(), "甜宠", 3)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("标题或简介");
    }

    /**
     * 构造服务。
     */
    private CreatorCopilotService service(ChatClient chatClient) {
        return new CreatorCopilotService(chatClient, properties, new AiInputGuard(),
                new AiOutputGuard(), quotaService, new AiMetrics(null));
    }

    /**
     * 放行配额。
     */
    private void allowQuota() {
        when(quotaService.consume("creator_copilot", 1003L, properties.getDailyLimit()))
                .thenReturn(new AiQuotaService.QuotaResult(true, 1,
                        properties.getDailyLimit() - 1));
    }

    /**
     * 构造请求。
     */
    private CreatorCopilotRequest request() {
        return new CreatorCopilotRequest("隐婚总裁的甜妻", "先婚后爱",
                "普通女孩苏晚意外闪婚商圈传奇顾沉舟。", "林一,苏晚",
                List.of("第1集 闪婚", "第2集 同居"), "甜宠", 3);
    }

    /**
     * 构造版本建议。
     */
    private CreatorCopilotVersion version(String title, String subtitle, String rationale) {
        return new CreatorCopilotVersion(title, subtitle, List.of("甜宠", "先婚后爱"),
                "冲突前置", rationale);
    }

    /**
     * 构造固定返回建议的 ChatClient。
     */
    private ChatClient chatClient(CreatorCopilotSuggestion suggestion) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString())
                .call().entity(CreatorCopilotSuggestion.class)).thenReturn(suggestion);
        return chatClient;
    }
}
