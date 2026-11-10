package com.msd.content.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 中文 BM25 预分词器。
 *
 * <p>索引和查询共用 jieba，索引写入空格分隔的 token，Elasticsearch 侧只配置
 * whitespace analyzer，避免 standard analyzer 把中文拆成单字。</p>
 */
@Component
@ConditionalOnProperty(prefix = "msd.ai.rag", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ChineseTokenizer {

    /** 查询侧停用词，只过滤口语虚词，不影响题材和情绪词。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "有", "没有", "那种", "很", "想", "看", "剧", "短剧",
            "一个", "一部", "有没有", "什么", "推荐", "可以", "我", "你", "他", "她", "它",
            "我们", "你们", "他们", "这个", "那个", "这些", "那些", "吗", "呢", "吧", "啊",
            "呀", "哦", "和", "与", "或", "在", "就", "都", "也", "还", "又", "被", "把",
            "给", "对", "从", "到", "中", "里", "上", "下");

    /** jieba 分词器不是并发安全的，按线程隔离。 */
    private final ThreadLocal<JiebaSegmenter> segmenter = ThreadLocal.withInitial(JiebaSegmenter::new);

    /**
     * 查询侧分词：过滤停用词、标点和重复 token。
     */
    public List<String> tokenizeForQuery(String text) {
        return tokenize(text, true);
    }

    /**
     * 索引侧分词：保留停用词，保证和查询侧使用同一套 jieba 词元。
     */
    public List<String> tokenizeForIndex(String text) {
        return tokenize(text, false);
    }

    /**
     * 索引侧分词并拼接成空格分隔字符串。
     */
    public String joinForIndex(String text) {
        return String.join(" ", tokenizeForIndex(text));
    }

    /**
     * 按 SEARCH 模式分词，过滤空 token、纯标点和重复项。
     */
    private List<String> tokenize(String text, boolean filterStopWords) {
        // 1. 空文本直接返回，避免 jieba 处理空白输入
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 2. SEARCH 模式兼顾长词和子词，适合召回
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (SegToken segToken : segmenter.get().process(text, JiebaSegmenter.SegMode.SEARCH)) {
            String token = normalize(segToken.word);
            if (token == null) {
                continue;
            }
            // 3. 查询侧只过滤虚词；索引侧保留全部有效 token
            if (filterStopWords && STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return new ArrayList<>(tokens);
    }

    /**
     * 归一化单个 token：去空白、转小写、丢弃纯标点。
     */
    private String normalize(String word) {
        if (word == null) {
            return null;
        }
        String token = word.trim();
        if (token.isEmpty()) {
            return null;
        }
        boolean hasLetterOrDigit = token.codePoints().anyMatch(Character::isLetterOrDigit);
        if (!hasLetterOrDigit) {
            return null;
        }
        return token.toLowerCase(Locale.ROOT);
    }
}
