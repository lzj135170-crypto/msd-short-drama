package com.msd.content.audit;

import com.msd.content.config.AuditProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 结构化审核 JSON 映射和降级测试。
 */
class StructuredAuditServiceTest {

    private final ChatClient auditChatClient = mock(ChatClient.class,
            withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
    private final AuditProperties properties = new AuditProperties();
    private final StructuredAuditService service =
            new StructuredAuditService(auditChatClient, properties);

    /**
     * 正常返回结构化结论，违规原因和标签会做归一化。
     */
    @Test
    void returnsStructuredVerdict() {
        stubVerdict(new AuditVerdict(true, " 涉及未成年人 ", List.of("minor", "unknown")));

        AuditCheckResponse response = service.audit(
                new AuditCheckRequest("未成年人深夜独自进入酒吧消费", "drama_description"));

        assertThat(response.degraded()).isFalse();
        assertThat(response.degradedFlags()).isEmpty();
        assertThat(response.verdict().isViolation()).isTrue();
        assertThat(response.verdict().violationReason()).isEqualTo("涉及未成年人");
        assertThat(response.verdict().tags()).containsExactly("minor");
    }

    /**
     * 未违规时强制清空原因和标签。
     */
    @Test
    void normalizesCleanVerdict() {
        stubVerdict(new AuditVerdict(false, "模型多余原因", List.of("porn")));

        AuditCheckResponse response = service.audit(
                new AuditCheckRequest("今天天气不错", "comment"));

        assertThat(response.verdict().isViolation()).isFalse();
        assertThat(response.verdict().violationReason()).isEmpty();
        assertThat(response.verdict().tags()).isEmpty();
    }

    /**
     * 第一次解析失败后重试成功。
     */
    @Test
    void retriesOnceAndSucceeds() {
        when(auditChatClient.prompt().system(anyString()).user(anyString()).call()
                .entity(AuditVerdict.class))
                .thenThrow(new IllegalArgumentException("Could not parse JSON"))
                .thenReturn(new AuditVerdict(true, "暴力血腥", List.of("violence")));
        clearInvocations(auditChatClient);

        AuditCheckResponse response = service.audit(
                new AuditCheckRequest("大量血腥画面", "drama_description"));

        assertThat(response.degraded()).isFalse();
        assertThat(response.verdict().tags()).containsExactly("violence");
        verify(auditChatClient, times(2)).prompt();
    }

    /**
     * 两次解析失败返回 verdict=null 和 audit_parse_error。
     */
    @Test
    void marksParseErrorAfterRetry() {
        when(auditChatClient.prompt().system(anyString()).user(anyString()).call()
                .entity(AuditVerdict.class))
                .thenThrow(new IllegalArgumentException("Could not parse JSON"));
        clearInvocations(auditChatClient);

        AuditCheckResponse response = service.audit(
                new AuditCheckRequest("待审核内容", "comment"));

        assertThat(response.verdict()).isNull();
        assertThat(response.degraded()).isTrue();
        assertThat(response.degradedFlags())
                .containsExactly(StructuredAuditService.FLAG_PARSE);
        verify(auditChatClient, times(2)).prompt();
    }

    /**
     * 模型返回空对象时同样按解析失败处理。
     */
    @Test
    void marksParseErrorWhenVerdictNull() {
        stubVerdict(null);

        AuditCheckResponse response = service.audit(
                new AuditCheckRequest("待审核内容", "comment"));

        assertThat(response.verdict()).isNull();
        assertThat(response.degradedFlags())
                .containsExactly(StructuredAuditService.FLAG_PARSE);
    }

    /**
     * 超时标记 audit_timeout。
     */
    @Test
    void marksTimeout() {
        when(auditChatClient.prompt().system(anyString()).user(anyString()).call()
                .entity(AuditVerdict.class))
                .thenThrow(new RuntimeException(new TimeoutException("request timed out")));

        AuditCheckResponse response = service.audit(
                new AuditCheckRequest("待审核内容", "comment"));

        assertThat(response.verdict()).isNull();
        assertThat(response.degradedFlags())
                .containsExactly(StructuredAuditService.FLAG_TIMEOUT);
    }

    /**
     * 其他远程异常标记 audit_unavailable。
     */
    @Test
    void marksUnavailable() {
        when(auditChatClient.prompt().system(anyString()).user(anyString()).call()
                .entity(AuditVerdict.class))
                .thenThrow(new IllegalStateException("connection refused"));

        AuditCheckResponse response = service.audit(
                new AuditCheckRequest("待审核内容", "comment"));

        assertThat(response.verdict()).isNull();
        assertThat(response.degradedFlags())
                .containsExactly(StructuredAuditService.FLAG_UNAVAILABLE);
    }

    /**
     * 构造模型返回。
     */
    private void stubVerdict(AuditVerdict verdict) {
        when(auditChatClient.prompt().system(anyString()).user(anyString()).call()
                .entity(AuditVerdict.class)).thenReturn(verdict);
    }
}
