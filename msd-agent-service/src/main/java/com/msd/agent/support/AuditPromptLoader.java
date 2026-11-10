package com.msd.agent.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 读取 audit-agent 的 Markdown 规格，拆出 front matter 描述和 System Prompt 正文。
 *
 * <p>A2A 服务需要独立于 Supervisor 工作区运行，因此这里不依赖 Harness 的子 Agent
 * 加载器，直接把同一份规格文件作为提示词来源。</p>
 */
@Component
@Profile("audit")
public class AuditPromptLoader {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AuditPromptLoader.class);

    /** 规格文件缺失时的兜底描述。 */
    private static final String DEFAULT_DESCRIPTION = "码上短剧内容合规审核 Agent";

    /** 规格文件缺失时的兜底提示词。 */
    private static final String DEFAULT_BODY = """
            你是「码上短剧」的内容合规审核 Agent。
            审核标题、简介、剧集信息和评论中的敏感、低俗、暴力、侵权、虚假宣传等风险。
            只标记有明确文本依据的问题，给出结论、风险等级、命中问题和最小修改建议。
            """;

    // audit-agent.md 规格文件路径，相对路径按进程工作目录解析
    private final String promptFile;

    /**
     * 注入规格文件路径；相对路径按进程工作目录解析。
     */
    public AuditPromptLoader(
            @Value("${msd.ai.a2a.audit.prompt-file:.agentscope/workspace/subagents/audit-agent.md}")
            String promptFile) {
        this.promptFile = promptFile;
    }

    /**
     * 加载并解析 audit 规格；文件不存在或解析失败时回退到内置提示词。
     */
    public AuditPrompt load() {
        Path path = Path.of(promptFile).toAbsolutePath().normalize();
        // 1. 文件不存在时使用内置提示词
        if (!Files.isRegularFile(path)) {
            log.warn("Audit prompt file not found: {}, using built-in prompt", path);
            return new AuditPrompt(DEFAULT_DESCRIPTION, DEFAULT_BODY);
        }
        try {
            // 2. 读取并解析，失败同样回退到内置提示词
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            log.warn("Failed to read audit prompt file: {}, using built-in prompt", path, ex);
            return new AuditPrompt(DEFAULT_DESCRIPTION, DEFAULT_BODY);
        }
    }

    /**
     * 解析 YAML front matter 和正文。
     */
    private AuditPrompt parse(String content) {
        List<String> lines = content.lines().toList();
        String description = DEFAULT_DESCRIPTION;
        int bodyStart = 0;

        // 1. 只有首行是 --- 才按 front matter 解析，避免误伤正文分隔线
        if (!lines.isEmpty() && "---".equals(lines.getFirst().trim())) {
            int end = -1;
            for (int index = 1; index < lines.size(); index += 1) {
                if ("---".equals(lines.get(index).trim())) {
                    end = index;
                    break;
                }
            }
            if (end > 0) {
                // 2. 取 description 字段；其余 front matter（workspace/tools）由调用方决定
                for (int index = 1; index < end; index += 1) {
                    String line = lines.get(index).trim();
                    if (line.startsWith("description:")) {
                        String value = line.substring("description:".length()).trim();
                        description = stripQuotes(value);
                        break;
                    }
                }
                bodyStart = end + 1;
            }
        }

        // 3. 正文为空时回退，避免 ReActAgent 没有 system prompt
        String body = String.join("\n", lines.subList(bodyStart, lines.size())).trim();
        if (!StringUtils.hasText(body)) {
            body = DEFAULT_BODY;
        }
        return new AuditPrompt(
                StringUtils.hasText(description) ? description : DEFAULT_DESCRIPTION, body);
    }

    /**
     * 去掉简单 YAML 引号。
     */
    private String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * audit 提示词解析结果。
     *
     * @param description 展示在 AgentCard 和 A2A 描述中的一句话
     * @param body        ReActAgent 的 system prompt
     */
    public record AuditPrompt(String description, String body) {
    }
}
