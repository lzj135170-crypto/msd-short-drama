package com.msd.content.rag;

import com.msd.content.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 使用 LLM 把用户口语化需求改写成检索关键词。
 *
 * <p>改写失败时保留原查询，并进入短暂退避，避免每个请求都等待模型超时。
 * 该类由 RagConfig 在 4.9 中装配为 Bean。</p>
 */
public class LlmQueryTransformer implements QueryTransformer {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(LlmQueryTransformer.class);

    /** 改写系统提示，RagConfig 构建 rewriteChatClient 时复用。 */
    public static final String SYSTEM_PROMPT = """
            你是“码上短剧”的检索查询改写器。把用户的口语化需求改写成适合短剧检索的关键词。
            要求：
            1. 只输出关键词，用空格分隔，不要输出解释、标点或“关键词：”之类的前缀。
            2. 保留题材、人物关系、情绪、剧情元素、剧名和演员。
            3. 不要编造用户没有提到的信息。
            4. 总长度不超过 20 个汉字。
            """;

    /** 改写结果最大字符数。 */
    private static final int MAX_REWRITTEN_CHARS = 80;

    // 改写专用 ChatClient
    private final ChatClient rewriteChatClient;
    // 改写配置
    private final RagProperties properties;
    // 执行改写的限时线程池
    private final Executor rewriteExecutor;

    // 退避截止时间，期内不再调用模型
    private volatile long unavailableUntil;

    /**
     * 注入专用 ChatClient、改写配置和限时线程池。
     */
    public LlmQueryTransformer(ChatClient rewriteChatClient,
                               RagProperties properties,
                               Executor rewriteExecutor) {
        this.rewriteChatClient = rewriteChatClient;
        this.properties = properties;
        this.rewriteExecutor = rewriteExecutor;
    }

    /**
     * 改写查询；任何异常都回退原查询。
     */
    @Override
    public Query transform(Query query) {
        // 1. 关闭改写或空查询直接透传
        if (!properties.getRewrite().isEnabled() || query == null
                || query.text() == null || query.text().isBlank()) {
            return query;
        }

        RagTrace trace = RagTrace.from(query);
        long startedAt = System.currentTimeMillis();
        String original = query.text();

        // 2. 退避期内不再调用模型
        if (System.currentTimeMillis() < unavailableUntil) {
            markFailure(trace, startedAt, null);
            return query;
        }

        CompletableFuture<String> future = null;
        try {
            // 3. 实验命中时用轻量模型改写，否则沿用默认模型
            ChatClient.ChatClientRequestSpec requestSpec = rewriteChatClient.prompt()
                    .user(original);
            if (trace != null && trace.getRewriteModel() != null
                    && !trace.getRewriteModel().isBlank()) {
                requestSpec = requestSpec.options(DeepSeekChatOptions.builder()
                        .model(trace.getRewriteModel())
                        .temperature(0.1));
            }
            ChatClient.ChatClientRequestSpec effectiveSpec = requestSpec;
            // 4. 独立线程池 + 超时，避免改写拖垮整条 RAG 链路
            future = CompletableFuture.supplyAsync(
                    () -> effectiveSpec.call().content(), rewriteExecutor);
            String rewritten = clean(future.get(properties.getRewrite().getTimeoutMs(),
                    TimeUnit.MILLISECONDS));

            // 4. 空结果视为失败，回退原查询
            if (rewritten.isEmpty()) {
                markFailure(trace, startedAt, null);
                return query;
            }

            if (trace != null) {
                trace.setRewrittenQuery(rewritten);
                trace.setRewriteMillis(System.currentTimeMillis() - startedAt);
            }
            // 5. 显式保留 history 和 context，trace 才能继续传给检索器
            return new Query(rewritten, query.history(), query.context());
        } catch (Exception e) {
            if (future != null) {
                future.cancel(true);
            }
            markFailure(trace, startedAt, e);
            return query;
        }
    }

    /**
     * 记录改写失败、退避时间并写入 trace。
     */
    private void markFailure(RagTrace trace, long startedAt, Exception error) {
        unavailableUntil = System.currentTimeMillis() + properties.getRewrite().getFailureBackoffMs();
        if (trace != null) {
            trace.setRewriteFailed(true);
            trace.setRewriteMillis(System.currentTimeMillis() - startedAt);
            trace.markDegraded("rewrite");
        }
        if (error != null) {
            log.warn("query rewrite failed, fallback to original: {}", rootMessage(error));
        }
    }

    /**
     * 清理模型输出：去前缀、换行和标点，压缩空白并限制长度。
     */
    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        // 1. 去掉模型偶尔输出的前缀
        text = text.replaceFirst("(?i)^\\s*(关键词|改写|查询|结果)\\s*[：:]\\s*", "");
        // 2. 换行和引号、括号、中文标点都转为空格
        text = text.replace('\n', ' ').replace('\r', ' ');
        text = text.replaceAll("[`\"'“”‘’《》<>【】\\[\\]()（）]", " ");
        text = text.replaceAll("[，。！？；：、,.!?;:]", " ");
        // 3. 压缩连续空白
        text = text.replaceAll("\\s+", " ").trim();
        // 4. 兜底截断，避免模型输出整段解释
        if (text.length() > MAX_REWRITTEN_CHARS) {
            text = text.substring(0, MAX_REWRITTEN_CHARS).trim();
        }
        return text;
    }

    /**
     * 取最内层异常信息。
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                : message;
    }
}
