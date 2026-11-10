package com.msd.content.audit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核结果 JSON 的向后兼容和 advisory 序列化测试。
 */
class DramaAiReviewResultTest {

    private final JsonMapper jsonMapper = JsonMapper.shared();

    /**
     * 旧记录没有 advisory 仍可解析。
     */
    @Test
    void readsLegacyResultWithoutAdvisory() {
        String json = """
                {"scene":"drama_description",
                 "verdict":{"is_violation":false,"violation_reason":"","tags":[]},
                 "degraded":false,"degradedFlags":[],"elapsedMs":1200,
                 "auditedAt":"2026-10-27T12:00:00+08:00"}
                """;

        DramaAiReviewResult result = jsonMapper.readValue(json, DramaAiReviewResult.class);

        assertThat(result.verdict().isViolation()).isFalse();
        assertThat(result.advisory()).isNull();
    }

    /**
     * 新记录的 advisory 可解析，旧构造器仍按无建议处理。
     */
    @Test
    void readsAndWritesAdvisory() {
        DramaReviewAdvisory advisory = new DramaReviewAdvisory(
                "iic/nlp_deberta_rex-uninlu_chinese-base", "v1.0.2",
                new DramaSentimentAdvisory("negative", "负向情感", true, 2,
                        List.of("刑警追查十年悬案")),
                List.of(new DramaTagSuggestion("悬疑", 2, List.of("雨夜连环案"))),
                2, 0, false, false, List.of(), 842);
        DramaAiReviewResult result = new DramaAiReviewResult(
                "drama_description", new AuditVerdict(false, "", List.of()),
                false, List.of(), 1200, "2026-10-27T12:00:00+08:00", advisory);

        String json = jsonMapper.writeValueAsString(result);
        DramaAiReviewResult parsed = jsonMapper.readValue(json, DramaAiReviewResult.class);

        assertThat(parsed.advisory().sentiment().highNegative()).isTrue();
        assertThat(parsed.advisory().tags()).singleElement()
                .satisfies(tag -> assertThat(tag.name()).isEqualTo("悬疑"));
    }

    /**
     * 新结果写入 requestId，供审核反馈关联。
     */
    @Test
    void persistsRequestId() {
        DramaAiReviewResult result = new DramaAiReviewResult(
                "drama_description", new AuditVerdict(false, "", List.of()),
                false, List.of(), 1200, "2026-10-27T12:00:00+08:00", null,
                "audit-request-1");

        String json = jsonMapper.writeValueAsString(result);
        DramaAiReviewResult parsed = jsonMapper.readValue(json, DramaAiReviewResult.class);

        assertThat(parsed.requestId()).isEqualTo("audit-request-1");
    }

    /**
     * withAdvisory(null) 可从结果中移除建议。
     */
    @Test
    void removesAdvisory() {
        DramaAiReviewResult result = new DramaAiReviewResult(
                "drama_description", new AuditVerdict(false, "", List.of()),
                false, List.of(), 1200, "2026-10-27T12:00:00+08:00",
                DramaReviewAdvisory.degraded("rex_unavailable", 5));

        String json = jsonMapper.writeValueAsString(result.withAdvisory(null));

        assertThat(json).doesNotContain("rex_unavailable");
    }
}
