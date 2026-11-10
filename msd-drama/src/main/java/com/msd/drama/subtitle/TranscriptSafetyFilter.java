package com.msd.drama.subtitle;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 字幕入库前的确定性安全过滤。
 *
 * <p>只处理明显联系方式和引流话术；命中高风险内容时丢弃整条片段，
 * 联系方式做掩码，避免字幕索引把广告带进剧情问答。</p>
 */
@Component
public class TranscriptSafetyFilter {

    /** 手机号。 */
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    /** 邮箱。 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 微信/QQ 联系方式。 */
    private static final Pattern CONTACT_ACCOUNT = Pattern.compile(
            "(?i)(?:加|联系|添加)?\\s*(?:微信|vx|qq)[:：号]?\\s*[A-Za-z0-9_-]{5,}");
    /** 高风险引流/违规话术。 */
    private static final Pattern BLOCKED = Pattern.compile(
            "刷单|私下转账|代充|博彩|出售账号|裸聊|约炮|成人视频|加微信|加vx|加qq");
    /** 联系方式掩码。 */
    private static final String MASK = "[联系方式已隐藏]";
    /** 单条字幕最大字符数。 */
    private static final int MAX_CHARS = 1000;

    /**
     * 过滤一条字幕文本。
     *
     * @return 可入库文本；命中高风险内容时返回 null
     */
    public String sanitize(String text) {
        // 1. 空文本和超长文本直接丢弃
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() > MAX_CHARS) {
            return null;
        }
        // 2. 高风险引流话术整条丢弃
        if (BLOCKED.matcher(clean).find()) {
            return null;
        }
        // 3. 联系方式做掩码，保留剧情文本
        clean = PHONE.matcher(clean).replaceAll(MASK);
        clean = EMAIL.matcher(clean).replaceAll(MASK);
        clean = CONTACT_ACCOUNT.matcher(clean).replaceAll(MASK);
        return clean.isBlank() ? null : clean;
    }
}
