package com.msd.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具权限沙箱测试。
 */
class ToolGuardTest {

    /**
     * 白名单内且参数合法的工具调用放行。
     */
    @Test
    void allowsValidBusinessTool() {
        ToolGuard guard = guard();

        assertThatCode(() -> guard.validate("get_drama_detail", Map.of("dramaId", 5001L)))
                .doesNotThrowAnyException();
    }

    /**
     * 不在白名单的工具直接拒绝。
     */
    @Test
    void rejectsUnknownTool() {
        ToolGuard guard = guard();

        assertThatThrownBy(() -> guard.validate("shell_execute", Map.of("command", "dir")))
                .isInstanceOf(ToolAccessException.class)
                .hasMessageContaining("白名单");
    }

    /**
     * 非法的短剧/剧集 ID 必须在进入业务层前拒绝。
     */
    @Test
    void rejectsInvalidIds() {
        ToolGuard guard = guard();

        assertThatThrownBy(() -> guard.validate("get_drama_detail", Map.of("dramaId", 0L)))
                .isInstanceOf(ToolAccessException.class)
                .hasMessageContaining("dramaId");
        assertThatThrownBy(() -> guard.validate(
                "search_drama_context", Map.of("query", "前情", "currentEpisodeId", -1L)))
                .isInstanceOf(ToolAccessException.class)
                .hasMessageContaining("currentEpisodeId");
    }

    /**
     * 查询条件和审核内容有长度上限，避免超长 Prompt 拖垮模型。
     */
    @Test
    void rejectsOversizedText() {
        ToolGuard guard = guard();
        String longQuery = "前".repeat(501);

        assertThatThrownBy(() -> guard.validate(
                "search_drama_context", Map.of("query", longQuery, "currentEpisodeId", 6005L)))
                .isInstanceOf(ToolAccessException.class)
                .hasMessageContaining("query");
    }

    /**
     * limit/topK 等分页参数不得超过配置上限。
     */
    @Test
    void rejectsOversizedLimit() {
        ToolGuard guard = guard();

        assertThatThrownBy(() -> guard.validate(
                "search_drama_context",
                Map.of("query", "前情", "currentEpisodeId", 6005L, "topK", 51)))
                .isInstanceOf(ToolAccessException.class)
                .hasMessageContaining("topK");
    }

    /**
     * 关闭开关后不拦截，便于故障时回退。
     */
    @Test
    void bypassesWhenDisabled() {
        ToolGuard guard = guard();
        guard.setEnabled(false);

        assertThatCode(() -> guard.validate("shell_execute", Map.of("command", "dir")))
                .doesNotThrowAnyException();
    }

    /**
     * 调用方传入的 expected 列表必须进一步裁剪工具，audit 只能暴露两个只读工具。
     */
    @Test
    void filtersByExpectedAllowlist() {
        ToolGuard guard = guard();
        guard.setAllow(List.of("get_drama_detail", "get_home_dramas"));

        List<io.agentscope.core.tool.AgentTool> filtered = guard.filterAndValidate(
                List.of(new StubTool("get_drama_detail"), new StubTool("get_home_dramas")),
                List.of("get_drama_detail"));

        org.assertj.core.api.Assertions.assertThat(filtered)
                .extracting(io.agentscope.core.tool.AgentTool::getName)
                .containsExactly("get_drama_detail");
    }

    /**
     * 全局 allow 为空时仍必须遵守调用方的 expected 裁剪，audit 不能暴露全部工具。
     */
    @Test
    void emptyAllowStillHonorsExpected() {
        ToolGuard guard = guard();
        guard.setAllow(List.of());

        List<io.agentscope.core.tool.AgentTool> filtered = guard.filterAndValidate(
                List.of(new StubTool("get_drama_detail"), new StubTool("get_home_dramas")),
                List.of("get_drama_detail"));

        org.assertj.core.api.Assertions.assertThat(filtered)
                .extracting(io.agentscope.core.tool.AgentTool::getName)
                .containsExactly("get_drama_detail");
    }

    /**
     * 全局 allow 为空表示不限制工具名，但参数边界仍然生效。
     */
    @Test
    void emptyAllowSkipsWhitelistButKeepsValidation() {
        ToolGuard guard = guard();
        guard.setAllow(List.of());

        assertThatCode(() -> guard.validate("get_home_dramas", Map.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validate("get_drama_detail", Map.of("dramaId", 0L)))
                .isInstanceOf(ToolAccessException.class);
    }

    /**
     * 配置了不存在的工具名时启动期失败，不能静默忽略。
     */
    @Test
    void failsFastForUnknownConfiguredTool() {
        ToolGuard guard = guard();
        guard.setAllow(List.of("get_drama_detail", "not_exists"));

        assertThatThrownBy(() -> guard.filterAndValidate(
                List.of(new StubTool("get_drama_detail")), guard.getAllow()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not_exists");
    }

    /**
     * 构造专用 ToolGuard，缩短测试文本上限。
     */
    private ToolGuard guard() {
        ToolGuard guard = new ToolGuard();
        guard.setEnabled(true);
        guard.setAllow(List.of(
                "get_drama_detail", "search_drama_context", "get_episode_summary"));
        guard.setMaxTextLength(5000);
        guard.setMaxQueryLength(500);
        guard.setMaxLimit(50);
        return guard;
    }

    /**
     * 只暴露 getName 的最小工具桩。
     */
    private record StubTool(String name) implements io.agentscope.core.tool.AgentTool {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock>
                callAsync(io.agentscope.core.tool.ToolCallParam param) {
            return reactor.core.publisher.Mono.just(
                    io.agentscope.core.message.ToolResultBlock.text(""));
        }
    }
}
