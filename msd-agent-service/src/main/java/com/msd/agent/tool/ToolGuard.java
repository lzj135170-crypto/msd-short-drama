package com.msd.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 工具权限沙箱：白名单、参数边界和调用审计。
 *
 * <p>只覆盖 Spring AI 业务工具；AgentScope 内置 shell/文件/Web/记忆工具
 * 由 HarnessAgent 的 disable* 和 toolsConfig deny 列表关闭。</p>
 */
@Component
@ConfigurationProperties(prefix = "msd.ai.agent.tools")
public class ToolGuard {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(ToolGuard.class);

    /** 默认只读业务工具白名单。 */
    private static final List<String> DEFAULT_ALLOW = List.of(
            "get_home_dramas",
            "get_drama_detail",
            "list_drama_episodes",
            "get_episode_summary",
            "search_drama_context",
            "search_drama_rag",
            "audit_remote");

    /** 是否启用沙箱；关闭时保留旧行为。 */
    private boolean enabled = true;
    /** 允许注册和调用的工具名。 */
    private List<String> allow = new ArrayList<>(DEFAULT_ALLOW);
    /** 单个字符串参数最大长度。 */
    private int maxTextLength = 5000;
    /** query 参数最大长度，防止把整段剧情塞进检索。 */
    private int maxQueryLength = 500;
    /** limit/topK 等分页参数最大上限。 */
    private int maxLimit = 50;

    /**
     * 注册前过滤 allowlist，并在配置了不存在的工具时启动期失败。
     */
    public List<AgentTool> filterAndValidate(List<AgentTool> tools, Collection<String> expected) {
        // 1. 全局 allow 为空时保留全部工具；expected 仍必须裁剪
        Set<String> allowed = allow == null || allow.isEmpty() ? null : new HashSet<>(allow);
        Set<String> expectedNames = expected == null ? null : new HashSet<>(expected);
        List<AgentTool> filtered = tools.stream()
                .filter(tool -> allowed == null || allowed.contains(tool.getName()))
                .filter(tool -> expectedNames == null || expectedNames.contains(tool.getName()))
                .toList();

        // 2. 启动期校验配置中的工具名，避免拼写错误导致工具静默缺失
        if (enabled && expected != null) {
            Set<String> present = new HashSet<>();
            filtered.forEach(tool -> present.add(tool.getName()));
            for (String name : expected) {
                if (!present.contains(name)) {
                    throw new IllegalStateException("配置了不存在的工具: " + name);
                }
            }
        }
        return filtered;
    }

    /**
     * 调用前检查工具名和参数边界。
     */
    public void validate(String toolName, Map<String, Object> input) {
        // 1. 开关关闭时完全旁路
        if (!enabled) {
            return;
        }
        // 2. 工具名必须在白名单；allow 为空表示不限制工具名
        if (!getAllow().isEmpty() && !getAllow().contains(toolName)) {
            throw new ToolAccessException("工具不在白名单: " + toolName);
        }
        if (input == null || input.isEmpty()) {
            return;
        }

        // 3. 逐项校验 ID、分页和文本长度
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (!StringUtils.hasText(name) || value == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("limit") || lower.equals("topk")) {
                long number = longValue(value);
                if (number > maxLimit) {
                    throw new ToolAccessException(name + " 不能超过 " + maxLimit);
                }
            }
            if (lower.endsWith("id") && value instanceof Number number && number.longValue() <= 0) {
                throw new ToolAccessException(name + " 必须为正整数");
            }
            if (value instanceof String text && text.length() > maxTextLength) {
                throw new ToolAccessException(name + " 不能超过 " + maxTextLength + " 字");
            }
            if ("query".equals(lower) && value instanceof String text
                    && text.length() > maxQueryLength) {
                throw new ToolAccessException("query 不能超过 " + maxQueryLength + " 字");
            }
        }
    }

    /**
     * 记录一次工具调用审计，不记录参数原文。
     */
    public void logCall(String toolName, ToolCallParam param, long startNanos,
                        String outcome, int resultLength) {
        // 1. 参数只记录哈希，用户文本不进日志
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        RuntimeContext context = param == null ? null : param.getRuntimeContext();
        String userId = context == null || !StringUtils.hasText(context.getUserId())
                ? "unknown" : context.getUserId();
        String sessionId = context == null || !StringUtils.hasText(context.getSessionId())
                ? "unknown" : context.getSessionId();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("agent.tool.audit tool={} user={} session={} argsHash={} "
                        + "elapsedMs={} outcome={} resultLength={}",
                toolName, userId, sessionId, argHash(input), elapsedMs, outcome, resultLength);
    }

    /**
     * 对参数做稳定哈希，仅用于排查重复调用。
     */
    private String argHash(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "-";
        }
        Map<String, String> sorted = new TreeMap<>();
        input.forEach((key, value) -> sorted.put(String.valueOf(key), String.valueOf(value)));
        return sha256(sorted.toString()).substring(0, 16);
    }

    /**
     * SHA-256 十六进制摘要。
     */
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                text.append(String.format("%02x", item));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Number/String 统一转 long。
     */
    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new ToolAccessException("分页参数必须为整数");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllow() {
        return allow;
    }

    public void setAllow(List<String> allow) {
        this.allow = allow == null ? new ArrayList<>() : allow;
    }

    public int getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(int maxTextLength) {
        this.maxTextLength = maxTextLength;
    }

    public int getMaxQueryLength() {
        return maxQueryLength;
    }

    public void setMaxQueryLength(int maxQueryLength) {
        this.maxQueryLength = maxQueryLength;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }
}
