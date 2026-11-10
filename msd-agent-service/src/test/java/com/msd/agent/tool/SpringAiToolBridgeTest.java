package com.msd.agent.tool;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI 工具桥接的沙箱集成测试。
 */
class SpringAiToolBridgeTest {

    /**
     * 非法参数在反射执行前被 ToolGuard 拒绝，业务方法不能被调用。
     */
    @Test
    void rejectsInvalidArgumentBeforeInvocation() {
        AtomicBoolean invoked = new AtomicBoolean();
        ToolGuard guard = new ToolGuard();
        guard.setAllow(List.of("get_drama_detail"));
        AgentTool tool = SpringAiToolBridge.adapt(new TestTools(invoked), guard).get(0);

        ToolResultBlock result = tool.callAsync(param(0L)).block();

        assertThat(invoked).isFalse();
        assertThat(result).isNotNull();
        assertThat(((TextBlock) result.getOutput().get(0)).getText())
                .contains("工具调用被拒绝")
                .contains("dramaId");
    }

    /**
     * 合法参数正常执行并返回业务文本。
     */
    @Test
    void invokesValidTool() {
        AtomicBoolean invoked = new AtomicBoolean();
        ToolGuard guard = new ToolGuard();
        guard.setAllow(List.of("get_drama_detail"));
        AgentTool tool = SpringAiToolBridge.adapt(new TestTools(invoked), guard).get(0);

        ToolResultBlock result = tool.callAsync(param(5001L)).block();

        assertThat(invoked).isTrue();
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).isEqualTo("drama-5001");
    }

    /**
     * 构造一次工具调用。
     */
    private ToolCallParam param(long dramaId) {
        Map<String, Object> input = Map.of("dramaId", dramaId);
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("call-1")
                        .name("get_drama_detail")
                        .input(input)
                        .build())
                .input(input)
                .build();
    }

    /**
     * 测试用业务工具。
     */
    private static final class TestTools {

        private final AtomicBoolean invoked;

        private TestTools(AtomicBoolean invoked) {
            this.invoked = invoked;
        }

        /**
         * 记录调用并返回短剧 ID。
         */
        @Tool(name = "get_drama_detail", description = "测试工具")
        public String detail(
                @ToolParam(description = "短剧ID", required = true) long dramaId) {
            invoked.set(true);
            return "drama-" + dramaId;
        }
    }
}
