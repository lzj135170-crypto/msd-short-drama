package com.msd.agent.routing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户文本 Prompt 防护：清洗伪造内部标记，拦截指令覆盖和系统提示套取。
 *
 * <p>用户文本永远是不可信数据；服务端内部标记只能由服务端生成。</p>
 */
@Component
public class PromptGuard {

    /** 服务端保留标记；用户输入中出现时一律移除。 */
    private static final Pattern RESERVED_MARKERS = Pattern.compile(
            "(?i)\\[\\s*(ROUTE_HINT|当前剧集ID|REMOTE|DEGRADED|SUMMARY_MISSING|"
                    + "CONTEXT_EMPTY|NOT_FOUND|EPISODE|SUMMARY|SOURCE|NOTICE|"
                    + "DRAMA_CARD|ANSWER|SOURCES|NO_RESULT)\\b[^\\]]*\\]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 中文指令覆盖/套取内部提示。 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(忽略|无视|忘记|绕过).{0,20}(以上|之前|前面|全部|所有|系统|开发者)"
                    + ".{0,20}(指令|提示|规则|约束|消息)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(系统提示|系统指令|开发者消息|内部指令|工具参数)"
                    + ".{0,20}(输出|泄露|打印|展示|告诉我|重复)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(输出|泄露|打印|展示|告诉我|重复)"
                    + ".{0,20}(系统提示|系统指令|开发者消息|内部指令|工具参数)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(你现在是|扮演|切换为).{0,20}(系统|开发者|管理员|root|越狱)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(ignore|disregard|forget|override).{0,20}"
                    + "(previous|above|prior|system|developer).{0,20}"
                    + "(instruction|prompt|rule|message)"),
            Pattern.compile("(?i)(reveal|print|show|repeat|leak).{0,20}"
                    + "(system prompt|developer message|instructions)"));

    /**
     * 检查并清洗用户消息。
     *
     * @param message 原始用户消息
     * @return 清洗后的文本、是否拒绝、原因和移除的标记名
     */
    public CheckResult inspect(String message) {
        String original = message == null ? "" : message;
        // 1. 先收集并移除服务端保留标记
        List<String> removed = new ArrayList<>();
        Matcher markerMatcher = RESERVED_MARKERS.matcher(original);
        while (markerMatcher.find()) {
            String name = markerMatcher.group(1);
            if (!removed.contains(name)) {
                removed.add(name);
            }
        }
        String cleaned = normalize(RESERVED_MARKERS.matcher(original).replaceAll(""));

        // 2. 再检查指令覆盖/系统提示套取
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(cleaned).find()) {
                return new CheckResult(cleaned, true,
                        "消息包含不被允许的指令注入内容", removed);
            }
        }
        return new CheckResult(cleaned, false, "", removed);
    }

    /**
     * 回复清洗：只移除服务端内部标记，保留 Markdown 换行。
     */
    public static String stripInternalMarkers(String text) {
        String cleaned = RESERVED_MARKERS.matcher(text == null ? "" : text).replaceAll("");
        // 1. 合并行内多余空格，清理行尾空白
        cleaned = cleaned.replaceAll("[ \\t]{2,}", " ")
                .replaceAll("[ \\t]+\\n", "\n");
        // 2. 最多保留一个空行，去掉首尾空白
        return cleaned.replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 合并多余空白，避免移除标记后留下大段空隙。
     */
    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * 检查结果。
     */
    public record CheckResult(
            String text,
            boolean rejected,
            String reason,
            List<String> removedMarkers) {
    }
}
