package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 多查询扩展器：把一条检索词扩展成原查询 + 偏好查询 + LLM 变体。
 *
 * <p>默认关闭；失败时保留原查询并标记 expansion 降级，不阻断 RAG 主链路。</p>
 */
public class LlmMultiQueryExpander implements QueryExpander {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(LlmMultiQueryExpander.class);
    /** 扩展系统提示。 */
    public static final String SYSTEM_PROMPT = """
            你是“码上短剧”的检索查询扩展器。根据用户需求生成不同角度的短剧检索关键词。
            要求：
            1. 只输出检索关键词，一行一条，不要编号、解释、标点或“查询：”之类的前缀。
            2. 保留题材、人物关系、情绪、剧情元素、剧名和演员；不同行尽量覆盖不同角度。
            3. 不要编造用户没有提到的信息。
            4. 每条不超过 20 个汉字。
            """;
    /** 单条查询最大字符数。 */
    private static final int MAX_QUERY_CHARS = 80;

    // 扩展专用 ChatClient
    private final ChatClient expandChatClient;
    // RAG 配置
    private final RagProperties properties;
    // 限时执行线程池
    private final Executor executor;

    // 退避截止时间，期内不再调用模型
    private volatile long unavailableUntil;

    /**
     * 注入扩展 ChatClient、配置和线程池。
     */
    public LlmMultiQueryExpander(ChatClient expandChatClient,
                                 RagProperties properties,
                                 Executor executor) {
        this.expandChatClient = expandChatClient;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * 扩展查询；关闭或失败时返回原查询。
     */
    @Override
    public List<Query> expand(Query query) {
        // 1. 未开启或空查询直接透传
        RagProperties.Expansion config = properties.getExpansion();
        if (config == null || !config.isEnabled() || query == null
                || !StringUtils.hasText(query.text())) {
            return query == null ? List.of() : List.of(query);
        }

        RagTrace trace = RagTrace.from(query);
        List<String> texts = new ArrayList<>();
        texts.add(query.text().trim());

        // 2. 把画像生成的偏好 query 放在原查询之后
        if (config.isIncludePreference() && trace != null
                && StringUtils.hasText(trace.getPreferenceQuery())) {
            addIfRoom(texts, trace.getPreferenceQuery(), config.getMaxQueries());
        }

        // 3. 需要额外变体且未处于退避期时调用轻量模型
        int wanted = Math.min(Math.max(config.getAdditionalQueries(), 0),
                Math.max(config.getMaxQueries() - texts.size(), 0));
        if (wanted > 0) {
            if (System.currentTimeMillis() < unavailableUntil) {
                markDegraded(trace);
            } else {
                List<String> generated = generate(query.text(), wanted, config.getTimeoutMs());
                if (generated.isEmpty()) {
                    markDegraded(trace);
                }
                for (String item : generated) {
                    if (texts.size() >= config.getMaxQueries()) {
                        break;
                    }
                    addIfRoom(texts, item, config.getMaxQueries());
                }
            }
        }

        // 4. 记录扩展结果，并把原查询的 history/context 传给每个变体
        if (trace != null) {
            trace.setExpandedQueries(texts);
        }
        return texts.stream()
                .map(text -> new Query(text, query.history(), query.context()))
                .toList();
    }

    /**
     * 调用模型生成查询变体；超时/异常时标记降级并进入退避。
     */
    private List<String> generate(String original, int count, long timeoutMs) {
        CompletableFuture<String> future = null;
        try {
            String prompt = "用户需求：" + original + "\n请生成 " + count + " 条不同角度的检索查询。";
            future = CompletableFuture.supplyAsync(
                    () -> expandChatClient.prompt().user(prompt).call().content(), executor);
            String raw = future.get(Math.max(timeoutMs, 100), TimeUnit.MILLISECONDS);
            return cleanLines(raw, count);
        } catch (Exception ex) {
            if (future != null) {
                future.cancel(true);
            }
            unavailableUntil = System.currentTimeMillis() + properties.getExpansion().getFailureBackoffMs();
            log.warn("multi-query expansion failed, fallback to original: {}", rootMessage(ex));
            return List.of();
        }
    }

    /**
     * 清理模型输出：按行拆分、去编号/符号、去重、限制长度。
     */
    private List<String> cleanLines(String raw, int limit) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        Set<String> lines = new LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String cleaned = line.trim()
                    .replaceFirst("^[-*•\\d.、\\s]+", "")
                    .replaceAll("^[\"'“”]+|[\"'“”]+$", "")
                    .replaceAll("[。；;]+$", "")
                    .trim();
            if (cleaned.isBlank()) {
                continue;
            }
            if (cleaned.length() > MAX_QUERY_CHARS) {
                cleaned = cleaned.substring(0, MAX_QUERY_CHARS);
            }
            lines.add(cleaned);
            if (lines.size() >= limit) {
                break;
            }
        }
        return List.copyOf(lines);
    }

    /**
     * 追加查询并去重，超过上限时停止。
     */
    private void addIfRoom(List<String> texts, String text, int maxQueries) {
        if (texts.size() >= maxQueries || !StringUtils.hasText(text)) {
            return;
        }
        String cleaned = text.trim();
        if (!texts.contains(cleaned)) {
            texts.add(cleaned);
        }
    }

    /**
     * 标记扩展降级。
     */
    private void markDegraded(RagTrace trace) {
        if (trace != null) {
            trace.markDegraded("expansion");
        }
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
