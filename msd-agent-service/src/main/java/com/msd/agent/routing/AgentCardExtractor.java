package com.msd.agent.routing;

import com.msd.agent.dto.AgentDramaCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Supervisor 回复中提取 DRAMA_CARD 行并移除。
 *
 * <p>工具返回标准格式，模型通常原样保留；这里做最后一道解析，
 * 前端不需要理解内部标记。</p>
 */
public final class AgentCardExtractor {

    /** 标准卡片行。 */
    private static final Pattern CARD = Pattern.compile(
            "\\[DRAMA_CARD]\\s*dramaId=(\\d+);\\s*title=([^;]*);\\s*"
                    + "subtitle=([^;]*);\\s*snippet=([^\\n]*)");

    /**
     * 工具类禁止实例化。
     */
    private AgentCardExtractor() {
    }

    /**
     * 提取卡片并从正文移除卡片行。
     */
    public static Extraction extract(String text) {
        if (text == null || text.isBlank()) {
            return new Extraction("", List.of());
        }
        Map<Long, AgentDramaCard> cards = new LinkedHashMap<>();
        Matcher matcher = CARD.matcher(text);
        StringBuilder cleaned = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            cleaned.append(text, last, matcher.start());
            last = matcher.end();
            try {
                long dramaId = Long.parseLong(matcher.group(1));
                if (dramaId > 0) {
                    cards.putIfAbsent(dramaId, new AgentDramaCard(
                            dramaId, clean(matcher.group(2)), clean(matcher.group(3)),
                            clean(matcher.group(4))));
                }
            } catch (NumberFormatException ignored) {
                // 非法 ID 只移除标记，不生成卡片
            }
            // 移除整行尾随空白
            int newline = text.indexOf('\n', last);
            if (newline >= 0) {
                cleaned.append('\n');
                last = newline + 1;
            }
        }
        cleaned.append(text.substring(Math.min(last, text.length())));
        return new Extraction(cleaned.toString().trim(), List.copyOf(cards.values()));
    }

    /**
     * 清理卡片字段。
     */
    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 提取结果。
     *
     * @param text  移除卡片行后的正文
     * @param cards 去重后的卡片
     */
    public record Extraction(String text, List<AgentDramaCard> cards) {
    }
}
